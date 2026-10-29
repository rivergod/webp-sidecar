//! 요청 허용(동시 처리 슬롯, 대기 상한)과 요청 시간 상한(ADR-0012, ADR-0017).
//!
//! - 슬롯: 동시에 libwebp 작업을 하는 요청 수(`max_concurrency`).
//! - 대기 상한: 슬롯을 기다리는 요청 수가 `max_queued_requests`에 닿으면 새 요청은 기다리지 않고 `UNAVAILABLE`
//!   ("server busy")로 거부한다. 상한 없는 대기는 지연을 클라이언트 데드라인 너머로 밀어 처리량을 무너뜨린다(research/0012).
//! - 시간 상한: 슬롯을 잡은 때부터 `max_request_ms`가 지나면 입력 수신, 프레임 사이, 응답 전송에서 멈추고 슬롯을 놓는다.
//!   libwebp 함수 한 번(정지 이미지 디코딩·인코딩 한 번) 안에서는 멈출 수 없다.

use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::sync::{OwnedSemaphorePermit, Semaphore, TryAcquireError, mpsc};
use tokio::time::Instant;
use tokio_stream::Stream;
use tonic::Status;

/// 대기와 시간 상한 설정(ADR-0017).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RequestPolicy {
    /// 슬롯을 기다릴 수 있는 요청 수. 0이면 상한이 없다.
    pub max_queued_requests: usize,
    /// 슬롯을 잡은 뒤 요청 하나가 쓸 수 있는 시간.
    pub max_request: Duration,
}

impl Default for RequestPolicy {
    /// ADR-0017 기본값: 대기 8건, 60초.
    fn default() -> Self {
        Self {
            max_queued_requests: 8,
            max_request: Duration::from_secs(60),
        }
    }
}

/// 슬롯과 대기 수를 관리한다. 스레드 안전하고, 모든 리스너(UDS, TCP)가 공유한다.
#[derive(Debug)]
pub struct Admission {
    permits: Arc<Semaphore>,
    waiting: Arc<AtomicUsize>,
    policy: RequestPolicy,
}

impl Admission {
    /// 슬롯 수(1 이상)와 정책으로 만든다.
    pub fn new(max_concurrency: usize, policy: RequestPolicy) -> Self {
        Self {
            permits: Arc::new(Semaphore::new(max_concurrency.max(1))),
            waiting: Arc::new(AtomicUsize::new(0)),
            policy,
        }
    }

    /// 지금 슬롯을 기다리는 요청 수.
    pub fn waiting(&self) -> usize {
        self.waiting.load(Ordering::SeqCst)
    }

    /// 슬롯을 잡는다. 빈 슬롯이 없으면 대기 상한 안에서 기다린다.
    ///
    /// 오류: 대기 상한에 닿았으면 `UNAVAILABLE`("server busy"), 서버가 끝나는 중이면 `UNAVAILABLE`.
    /// 기다리는 중에 클라이언트가 취소하면 tonic이 이 future를 버리고, 대기 수도 함께 줄어든다.
    pub async fn acquire(&self) -> Result<Slot, Status> {
        match self.permits.clone().try_acquire_owned() {
            Ok(permit) => return Ok(Slot::new(permit, self.policy.max_request)),
            Err(TryAcquireError::Closed) => return Err(Status::unavailable("server is shutting down")),
            Err(TryAcquireError::NoPermits) => {}
        }
        let max = self.policy.max_queued_requests;
        let queued = self.waiting.fetch_add(1, Ordering::SeqCst);
        let guard = WaitGuard(self.waiting.clone());
        if max > 0 && queued >= max {
            return Err(Status::unavailable(format!(
                "server busy: {queued} requests queued (max_queued_requests {max}); retry later"
            )));
        }
        let permit = self
            .permits
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| Status::unavailable("server is shutting down"))?;
        drop(guard);
        Ok(Slot::new(permit, self.policy.max_request))
    }
}

/// 대기 수를 놓을 때(슬롯을 얻거나 취소될 때) 줄인다.
struct WaitGuard(Arc<AtomicUsize>);

impl Drop for WaitGuard {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// 잡은 슬롯과 그 요청의 마감 시각. 버리면 슬롯이 풀린다.
#[derive(Debug)]
pub struct Slot {
    _permit: OwnedSemaphorePermit,
    deadline: Instant,
}

impl Slot {
    fn new(permit: OwnedSemaphorePermit, max_request: Duration) -> Self {
        Self {
            _permit: permit,
            deadline: Instant::now() + max_request,
        }
    }

    /// 마감 시각.
    pub fn deadline(&self) -> Instant {
        self.deadline
    }

    /// 마감이 지났으면 `DEADLINE_EXCEEDED`. 프레임 사이처럼 멈출 수 있는 지점에서 부른다.
    pub fn check(&self) -> Result<(), Status> {
        if Instant::now() >= self.deadline {
            Err(deadline_exceeded())
        } else {
            Ok(())
        }
    }
}

/// 서버 쪽 요청 시간 상한을 넘었다.
pub fn deadline_exceeded() -> Status {
    Status::deadline_exceeded("request exceeded max_request_ms on the server")
}

/// 응답 채널에 보낸 결과.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sent {
    /// 보냈다.
    Ok,
    /// 받는 쪽(클라이언트)이 떠났다.
    Gone,
    /// 마감까지 보내지 못했다(클라이언트가 읽지 않는다).
    Expired,
}

/// 마감까지만 기다리며 보낸다.
pub async fn send_until<T>(tx: &mpsc::Sender<T>, item: T, deadline: Instant) -> Sent {
    match tokio::time::timeout_at(deadline, tx.send(item)).await {
        Ok(Ok(())) => Sent::Ok,
        Ok(Err(_)) => Sent::Gone,
        Err(_) => Sent::Expired,
    }
}

/// [`send_until`]의 blocking 스레드 판. `spawn_blocking` 안에서 부른다.
pub fn blocking_send_until<T>(tx: &mpsc::Sender<T>, item: T, deadline: Instant) -> Sent {
    tokio::runtime::Handle::current().block_on(send_until(tx, item, deadline))
}

/// 응답 스트림에 마감을 건다. 보낸 항목을 먼저 내보내고, 마감이 지났는데 더 올 것이 없거나 생산자가 끝났으면
/// `DEADLINE_EXCEEDED`로 끝낸다. 생산자는 마감에 걸리면 채널이 가득 차 오류를 넣지 못한 채 끝날 수 있으므로, 마감 뒤의
/// 끝을 정상 종료로 보면 잘린 응답이 성공처럼 보인다. 요청 시간 상한은 "응답을 다 보낼 때까지"다(ADR-0017).
pub struct DeadlineStream<S> {
    inner: S,
    sleep: Pin<Box<tokio::time::Sleep>>,
    done: bool,
}

impl<S> DeadlineStream<S> {
    /// `inner`에 `deadline`을 건다.
    pub fn new(inner: S, deadline: Instant) -> Self {
        Self {
            inner,
            sleep: Box::pin(tokio::time::sleep_until(deadline)),
            done: false,
        }
    }
}

impl<S, T> Stream for DeadlineStream<S>
where
    S: Stream<Item = Result<T, Status>> + Unpin,
{
    type Item = Result<T, Status>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        if self.done {
            return Poll::Ready(None);
        }
        if let Poll::Ready(item) = Pin::new(&mut self.inner).poll_next(cx) {
            if item.is_some() {
                return Poll::Ready(item);
            }
            self.done = true;
            if Instant::now() >= self.sleep.deadline() {
                return Poll::Ready(Some(Err(deadline_exceeded())));
            }
            return Poll::Ready(None);
        }
        if self.sleep.as_mut().poll(cx).is_ready() {
            self.done = true;
            return Poll::Ready(Some(Err(deadline_exceeded())));
        }
        Poll::Pending
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy(max_queued: usize, max_request_ms: u64) -> RequestPolicy {
        RequestPolicy {
            max_queued_requests: max_queued,
            max_request: Duration::from_millis(max_request_ms),
        }
    }

    #[tokio::test]
    async fn acquire_beyond_queue_limit_is_unavailable_immediately() {
        // Arrange: 슬롯 1개를 잡고, 대기 1건을 세운다
        let admission = Arc::new(Admission::new(1, policy(1, 60_000)));
        let held = admission.acquire().await.unwrap();
        let waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire().await.map(|_| ()) })
        };
        while admission.waiting() < 1 {
            tokio::task::yield_now().await;
        }

        // Act
        let rejected = admission.acquire().await;

        // Assert
        assert_eq!(rejected.unwrap_err().code(), tonic::Code::Unavailable);
        drop(held);
        assert!(waiter.await.unwrap().is_ok());
        assert_eq!(admission.waiting(), 0);
    }

    #[tokio::test]
    async fn acquire_cancelled_while_waiting_releases_queue_place() {
        // Arrange
        let admission = Arc::new(Admission::new(1, policy(1, 60_000)));
        let _held = admission.acquire().await.unwrap();
        let waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire().await.map(|_| ()) })
        };
        while admission.waiting() < 1 {
            tokio::task::yield_now().await;
        }

        // Act
        waiter.abort();
        let _ = waiter.await;

        // Assert
        assert_eq!(admission.waiting(), 0);
    }

    #[tokio::test(start_paused = true)]
    async fn slot_check_after_max_request_is_deadline_exceeded() {
        // Arrange
        let admission = Admission::new(1, policy(0, 100));
        let slot = admission.acquire().await.unwrap();

        // Act
        tokio::time::advance(Duration::from_millis(150)).await;

        // Assert
        assert_eq!(slot.check().unwrap_err().code(), tonic::Code::DeadlineExceeded);
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_stream_ends_with_deadline_exceeded_when_producer_stalls() {
        // Arrange: 항목 하나를 보낸 뒤 생산자가 멈춘다(보내는 쪽은 살아 있다)
        let (tx, rx) = mpsc::channel::<Result<u32, Status>>(4);
        tx.send(Ok(7)).await.unwrap();
        let deadline = Instant::now() + Duration::from_millis(100);
        let mut stream = DeadlineStream::new(tokio_stream::wrappers::ReceiverStream::new(rx), deadline);

        // Act
        let first = tokio_stream::StreamExt::next(&mut stream).await;
        let second = tokio_stream::StreamExt::next(&mut stream).await;
        let third = tokio_stream::StreamExt::next(&mut stream).await;

        // Assert
        assert_eq!(first.unwrap().unwrap(), 7);
        assert_eq!(second.unwrap().unwrap_err().code(), tonic::Code::DeadlineExceeded);
        assert!(third.is_none());
        drop(tx);
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_stream_ending_after_deadline_reports_deadline_exceeded() {
        // Arrange: 생산자가 마감에 걸려 오류를 넣지 못하고 끝난다(송신기를 버린다)
        let (tx, rx) = mpsc::channel::<Result<u32, Status>>(4);
        tx.send(Ok(1)).await.unwrap();
        let deadline = Instant::now() + Duration::from_millis(100);
        let mut stream = DeadlineStream::new(tokio_stream::wrappers::ReceiverStream::new(rx), deadline);
        tokio::time::advance(Duration::from_millis(150)).await;
        drop(tx);

        // Act
        let first = tokio_stream::StreamExt::next(&mut stream).await;
        let second = tokio_stream::StreamExt::next(&mut stream).await;

        // Assert: 보낸 항목은 받고, 끝은 정상 종료가 아니라 DEADLINE_EXCEEDED다
        assert_eq!(first.unwrap().unwrap(), 1);
        assert_eq!(second.unwrap().unwrap_err().code(), tonic::Code::DeadlineExceeded);
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_stream_ending_before_deadline_is_normal() {
        // Arrange
        let (tx, rx) = mpsc::channel::<Result<u32, Status>>(4);
        tx.send(Ok(1)).await.unwrap();
        drop(tx);
        let deadline = Instant::now() + Duration::from_millis(100);
        let mut stream = DeadlineStream::new(tokio_stream::wrappers::ReceiverStream::new(rx), deadline);

        // Act
        let first = tokio_stream::StreamExt::next(&mut stream).await;
        let second = tokio_stream::StreamExt::next(&mut stream).await;

        // Assert
        assert_eq!(first.unwrap().unwrap(), 1);
        assert!(second.is_none());
    }
}
