//! 요청 허용(메모리 예산, 동시 처리 슬롯, 대기 상한)과 요청 시간 상한(ADR-0012, ADR-0016, ADR-0017).
//!
//! - 메모리 예산(ADR-0016): 요청마다 추정 메모리를 예산에서 예약한다. 예산은 `max_memory_bytes`에서 출력 버퍼 풀 총량
//!   상한을 고정으로 뺀 값이다(풀은 자기 상한 안에서 따로 관리하므로 합계가 `max_memory_bytes`를 넘지 않는다).
//!   혼자서도 예산을 넘는 요청은 기다리지 않고 `RESOURCE_EXHAUSTED`로 거부한다.
//! - 슬롯: 동시에 libwebp 작업을 하는 요청 수(`max_concurrency`). 메모리를 먼저 잡고 슬롯을 잡는다. 메모리를 못 잡은
//!   요청이 슬롯을 차지하지 않게 하려는 것이다. 메모리를 기다리는 동안에는 아무것도 쥐지 않으므로 교착이 없다.
//! - 대기 상한: 메모리나 슬롯을 기다리는 요청 수가 `max_queued_requests`에 닿으면 새 요청은 기다리지 않고
//!   `UNAVAILABLE`("server busy")로 거부한다. 상한 없는 대기는 지연을 클라이언트 데드라인 너머로 밀어 처리량을
//!   무너뜨린다(research/0012).
//! - 시간 상한: 슬롯을 잡은 때부터 `max_request_ms`가 지나면 입력 수신, 프레임 사이, 응답 전송에서 멈추고 슬롯을 놓는다.
//!   libwebp 함수 한 번(정지 이미지 디코딩·인코딩 한 번) 안에서는 멈출 수 없다.
//! - 요청 중간의 추가 예약([`Slot::ensure_reserved`])은 기다리지 않는다. 슬롯과 예약을 쥔 채 기다리면 서로 놓기를
//!   기다리는 교착이 생기기 때문이다(ADR-0016).

use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::sync::{OwnedSemaphorePermit, Semaphore, TryAcquireError, mpsc};
use tokio::time::Instant;
use tokio_stream::Stream;
use tonic::Status;

/// 메모리 예산의 셈 단위. 세마포어 허가 하나가 1 KiB다(`acquire_many`가 u32라 바이트 단위로는 4 GiB를 넘지 못한다).
const KIB: u64 = 1024;

/// 메모리 예산 허가 수의 상한. 예산은 이 값 × 1 KiB(4 TiB)를 넘을 수 없다.
pub const MAX_BUDGET_BYTES: u64 = u32::MAX as u64 * KIB;

/// 요청 허용 정책(ADR-0016, ADR-0017).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RequestPolicy {
    /// 메모리나 슬롯을 기다릴 수 있는 요청 수. 0이면 상한이 없다.
    pub max_queued_requests: usize,
    /// 슬롯을 잡은 뒤 요청 하나가 쓸 수 있는 시간.
    pub max_request: Duration,
    /// 진행 중인 요청들과 출력 버퍼 풀이 함께 쓸 수 있는 메모리 합계. 요청 몫은 여기서 풀 총량 상한을 뺀 값이다.
    /// 단위: 바이트.
    pub max_memory_bytes: u64,
}

impl Default for RequestPolicy {
    /// 기본값: 대기 8건, 60초(ADR-0017), 메모리 768 MiB(ADR-0016, 컨테이너 기본 제한 1 GiB의 75%).
    fn default() -> Self {
        Self {
            max_queued_requests: 8,
            max_request: Duration::from_secs(60),
            max_memory_bytes: 768 * 1024 * 1024,
        }
    }
}

/// 바이트 수를 KiB 허가 수로 올림한다.
fn kib_ceil(bytes: u64) -> u64 {
    bytes.div_ceil(KIB)
}

fn shutting_down() -> Status {
    Status::unavailable("server is shutting down")
}

/// 메모리 예산, 슬롯, 대기 수를 관리한다. 스레드 안전하고, 모든 리스너(UDS, TCP)가 공유한다.
#[derive(Debug)]
pub struct Admission {
    permits: Arc<Semaphore>,
    memory: Arc<Semaphore>,
    budget: Budget,
    waiting: Arc<AtomicUsize>,
    policy: RequestPolicy,
    /// 슬롯 수(`permits`의 전체 허가 수).
    slots: usize,
    /// 서버가 뜬 뒤 슬롯을 얻은 요청 수(누적).
    started: AtomicU64,
}

/// 요청 허용 쪽에서 본 서버 활동. 유휴 정리(ADR-0021 `malloc_trim`, [`crate::heap::IdleTrim`])가 쓴다.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Activity {
    /// 서버가 뜬 뒤 슬롯을 얻은 요청 수(누적).
    pub started: u64,
    /// 지금 슬롯이나 메모리 예약을 쥐었거나, 그것을 기다리는 요청이 있다.
    pub busy: bool,
}

/// 예산 크기(오류 메시지용).
#[derive(Debug, Clone, Copy)]
struct Budget {
    /// 요청 몫. 단위: KiB.
    kib: u64,
    /// `max_memory_bytes`. 단위: 바이트.
    max_memory_bytes: u64,
    /// 출력 버퍼 풀 총량 상한. 단위: 바이트.
    pool_bytes: u64,
}

impl Budget {
    fn bytes(&self) -> u64 {
        self.kib * KIB
    }

    fn describe(&self) -> String {
        format!(
            "memory budget {} bytes (max_memory_bytes {} - buffer pool {})",
            self.bytes(),
            self.max_memory_bytes,
            self.pool_bytes
        )
    }
}

impl Admission {
    /// 슬롯 수(1 이상), 정책, 출력 버퍼 풀 총량 상한(바이트)으로 만든다. 요청 몫의 예산은
    /// `policy.max_memory_bytes - pool_bytes`를 1 KiB 단위로 내림한 값이다(설정 검사가 양수를 보장한다).
    pub fn new(max_concurrency: usize, policy: RequestPolicy, pool_bytes: u64) -> Self {
        let kib = (policy.max_memory_bytes.saturating_sub(pool_bytes) / KIB).min(u64::from(u32::MAX));
        let slots = max_concurrency.max(1);
        Self {
            permits: Arc::new(Semaphore::new(slots)),
            memory: Arc::new(Semaphore::new(kib as usize)),
            budget: Budget {
                kib,
                max_memory_bytes: policy.max_memory_bytes,
                pool_bytes,
            },
            waiting: Arc::new(AtomicUsize::new(0)),
            policy,
            slots,
            started: AtomicU64::new(0),
        }
    }

    /// 슬롯을 잡은 뒤 요청 하나가 쓸 수 있는 시간(ADR-0017). 슬롯을 잡기 전의 머리 읽기에도 쓴다.
    pub fn max_request(&self) -> Duration {
        self.policy.max_request
    }

    /// 지금 메모리나 슬롯을 기다리는 요청 수.
    pub fn waiting(&self) -> usize {
        self.waiting.load(Ordering::SeqCst)
    }

    /// 요청 몫의 메모리 예산. 단위: 바이트.
    pub fn memory_budget_bytes(&self) -> u64 {
        self.budget.bytes()
    }

    /// 지금 예약되지 않은 예산. 단위: 바이트.
    pub fn available_memory_bytes(&self) -> u64 {
        self.memory.available_permits() as u64 * KIB
    }

    /// 지금의 활동 상태. 값들을 따로 읽으므로 읽는 사이에 요청이 시작될 수 있다(유휴 판단용 근사값).
    pub fn activity(&self) -> Activity {
        let busy = self.waiting() > 0
            || self.permits.available_permits() < self.slots
            || self.available_memory_bytes() < self.memory_budget_bytes();
        Activity {
            started: self.started.load(Ordering::SeqCst),
            busy,
        }
    }

    /// 대기 자리를 잡는다. 슬롯을 잡기 전에 입력 머리를 읽어야 하는 요청(디코딩 추정, ADR-0016)이 먼저 부른다.
    /// 머리를 들고 기다리는 요청 수도 대기 상한으로 묶으려는 것이다.
    ///
    /// 오류: 대기 상한에 닿았으면 `UNAVAILABLE`("server busy").
    pub fn enqueue(&self) -> Result<QueuePlace, Status> {
        let max = self.policy.max_queued_requests;
        let queued = self.waiting.fetch_add(1, Ordering::SeqCst);
        let place = QueuePlace(self.waiting.clone());
        if max > 0 && queued >= max {
            return Err(Status::unavailable(format!(
                "server busy: {queued} requests queued (max_queued_requests {max}); retry later"
            )));
        }
        Ok(place)
    }

    /// 추정 메모리 `estimate_bytes`를 예약하고 슬롯을 잡는다. 바로 잡을 수 없으면 대기 상한 안에서 메모리 → 슬롯
    /// 순서로 기다린다.
    ///
    /// 오류: 추정치가 예산보다 크면 기다리지 않고 `RESOURCE_EXHAUSTED`. 대기 상한에 닿았으면 `UNAVAILABLE`("server busy"),
    /// 서버가 끝나는 중이면 `UNAVAILABLE`. 기다리는 중에 클라이언트가 취소하면 tonic이 이 future를 버리고, 대기 수와
    /// 잡아 둔 메모리도 함께 돌아간다.
    pub async fn acquire(&self, estimate_bytes: u64) -> Result<Slot, Status> {
        self.acquire_inner(estimate_bytes, None).await
    }

    /// [`Admission::acquire`]와 같고, 이미 잡은 대기 자리([`Admission::enqueue`])를 쓴다. 자리는 슬롯을 얻거나
    /// 실패하면 돌아간다.
    pub async fn acquire_queued(&self, place: QueuePlace, estimate_bytes: u64) -> Result<Slot, Status> {
        self.acquire_inner(estimate_bytes, Some(place)).await
    }

    async fn acquire_inner(&self, estimate_bytes: u64, place: Option<QueuePlace>) -> Result<Slot, Status> {
        let kib = kib_ceil(estimate_bytes);
        if kib > self.budget.kib {
            return Err(Status::resource_exhausted(format!(
                "estimated memory {estimate_bytes} bytes exceeds the {}",
                self.budget.describe()
            )));
        }
        let kib = kib as u32;

        // 둘 다 바로 잡을 수 있으면 기다리지 않는다(대기 수에 넣지 않는다).
        let memory = match self.memory.clone().try_acquire_many_owned(kib) {
            Ok(m) => Some(m),
            Err(TryAcquireError::Closed) => return Err(shutting_down()),
            Err(TryAcquireError::NoPermits) => None,
        };
        let memory = match memory {
            Some(m) => match self.permits.clone().try_acquire_owned() {
                Ok(cpu) => return Ok(self.slot(cpu, m)),
                Err(TryAcquireError::Closed) => return Err(shutting_down()),
                Err(TryAcquireError::NoPermits) => Some(m),
            },
            None => None,
        };

        // 기다린다. 대기 상한에 걸리면 잡아 둔 메모리도 함께 놓는다.
        let _place = match place {
            Some(p) => p,
            None => self.enqueue()?,
        };
        let memory = match memory {
            Some(m) => m,
            None => self
                .memory
                .clone()
                .acquire_many_owned(kib)
                .await
                .map_err(|_| shutting_down())?,
        };
        let cpu = self
            .permits
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| shutting_down())?;
        Ok(self.slot(cpu, memory))
    }

    fn slot(&self, cpu: OwnedSemaphorePermit, memory: OwnedSemaphorePermit) -> Slot {
        self.started.fetch_add(1, Ordering::SeqCst);
        Slot {
            _cpu: cpu,
            reserved_kib: memory.num_permits() as u64,
            memory: Some(memory),
            semaphore: self.memory.clone(),
            budget: self.budget,
            deadline: Instant::now() + self.policy.max_request,
        }
    }
}

/// 대기 자리. 버리면(슬롯을 얻거나 취소될 때) 대기 수가 줄어든다.
#[derive(Debug)]
pub struct QueuePlace(Arc<AtomicUsize>);

impl Drop for QueuePlace {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// 잡은 슬롯, 예약한 메모리, 그 요청의 마감 시각. 버리면 슬롯과 (떼어 내지 않은) 예약이 풀린다.
#[derive(Debug)]
pub struct Slot {
    _cpu: OwnedSemaphorePermit,
    memory: Option<OwnedSemaphorePermit>,
    /// 이 요청이 지금까지 예약한 양(떼어 낸 몫 포함). 단위: KiB.
    reserved_kib: u64,
    semaphore: Arc<Semaphore>,
    budget: Budget,
    deadline: Instant,
}

impl Slot {
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

    /// 이 요청의 예약 합계가 `total_bytes` 이상이 되게 모자란 만큼 더 예약한다. **기다리지 않는다**(ADR-0016:
    /// 쥔 채 기다리면 교착). 애니메이션 인코딩의 프레임마다, 그리고 입력을 다 받은 뒤 추정치를 다시 셀 때 부른다.
    ///
    /// 오류: 지금 남은 예산이 모자라면 `RESOURCE_EXHAUSTED`. 호출자는 요청을 끝내 슬롯과 예약을 놓는다.
    pub fn ensure_reserved(&mut self, total_bytes: u64) -> Result<(), Status> {
        let want = kib_ceil(total_bytes);
        if want <= self.reserved_kib {
            return Ok(());
        }
        let more = want - self.reserved_kib;
        let exhausted = || {
            Status::resource_exhausted(format!(
                "memory budget exhausted: cannot reserve {more} KiB more without waiting (this request needs \
                 {total_bytes} bytes in total; {})",
                self.budget.describe()
            ))
        };
        if want > self.budget.kib {
            return Err(exhausted());
        }
        let permit = self
            .semaphore
            .clone()
            .try_acquire_many_owned(more as u32)
            .map_err(|_| exhausted())?;
        match self.memory.as_mut() {
            Some(m) => m.merge(permit),
            None => self.memory = Some(permit),
        }
        self.reserved_kib = want;
        Ok(())
    }

    /// 예약한 메모리를 떼어 낸다. 응답 버퍼에 붙여 두면 버퍼가 해제될 때 예약도 풀린다. 슬롯(CPU)은 그대로 쥔다.
    pub fn take_memory(&mut self) -> MemoryReservation {
        MemoryReservation {
            _permit: self.memory.take(),
        }
    }
}

/// 떼어 낸 메모리 예약. 버리면 예산으로 돌아간다.
#[derive(Debug)]
pub struct MemoryReservation {
    _permit: Option<OwnedSemaphorePermit>,
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
///
/// 끝나면(정상이든 마감이든) 안쪽 스트림을 바로 버린다. 채널에 남은 응답 조각이 출력 버퍼와 그 메모리 예약(ADR-0016)을
/// 붙잡지 않게 하려는 것이다.
pub struct DeadlineStream<S> {
    /// 끝나면 `None`.
    inner: Option<S>,
    sleep: Pin<Box<tokio::time::Sleep>>,
}

impl<S> DeadlineStream<S> {
    /// `inner`에 `deadline`을 건다.
    pub fn new(inner: S, deadline: Instant) -> Self {
        Self {
            inner: Some(inner),
            sleep: Box::pin(tokio::time::sleep_until(deadline)),
        }
    }
}

impl<S, T> Stream for DeadlineStream<S>
where
    S: Stream<Item = Result<T, Status>> + Unpin,
{
    type Item = Result<T, Status>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let Some(inner) = self.inner.as_mut() else {
            return Poll::Ready(None);
        };
        if let Poll::Ready(item) = Pin::new(inner).poll_next(cx) {
            if item.is_some() {
                return Poll::Ready(item);
            }
            self.inner = None;
            if Instant::now() >= self.sleep.deadline() {
                return Poll::Ready(Some(Err(deadline_exceeded())));
            }
            return Poll::Ready(None);
        }
        if self.sleep.as_mut().poll(cx).is_ready() {
            self.inner = None;
            return Poll::Ready(Some(Err(deadline_exceeded())));
        }
        Poll::Pending
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIB: u64 = 1024 * 1024;

    fn policy(max_queued: usize, max_request_ms: u64) -> RequestPolicy {
        RequestPolicy {
            max_queued_requests: max_queued,
            max_request: Duration::from_millis(max_request_ms),
            max_memory_bytes: 64 * MIB,
        }
    }

    /// 슬롯 `slots`개, 대기 상한 `max_queued`, 요청 몫 예산 `budget_mib` MiB(풀 0).
    fn admission(slots: usize, max_queued: usize, budget_mib: u64) -> Arc<Admission> {
        let policy = RequestPolicy {
            max_memory_bytes: budget_mib * MIB,
            ..policy(max_queued, 60_000)
        };
        Arc::new(Admission::new(slots, policy, 0))
    }

    async fn until_waiting(admission: &Admission, n: usize) {
        while admission.waiting() < n {
            tokio::task::yield_now().await;
        }
    }

    #[tokio::test]
    async fn acquire_beyond_queue_limit_is_unavailable_immediately() {
        // Arrange: 슬롯 1개를 잡고, 대기 1건을 세운다
        let admission = Arc::new(Admission::new(1, policy(1, 60_000), 0));
        let held = admission.acquire(1).await.unwrap();
        let waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire(1).await.map(|_| ()) })
        };
        while admission.waiting() < 1 {
            tokio::task::yield_now().await;
        }

        // Act
        let rejected = admission.acquire(1).await;

        // Assert
        assert_eq!(rejected.unwrap_err().code(), tonic::Code::Unavailable);
        drop(held);
        assert!(waiter.await.unwrap().is_ok());
        assert_eq!(admission.waiting(), 0);
    }

    #[tokio::test]
    async fn acquire_cancelled_while_waiting_releases_queue_place() {
        // Arrange
        let admission = Arc::new(Admission::new(1, policy(1, 60_000), 0));
        let _held = admission.acquire(1).await.unwrap();
        let waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire(1).await.map(|_| ()) })
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

    #[tokio::test]
    async fn activity_with_held_slot_is_busy() {
        // Arrange
        let admission = admission(1, 0, 1);
        let _held = admission.acquire(1).await.unwrap();

        // Act
        let activity = admission.activity();

        // Assert
        assert_eq!(activity, Activity { started: 1, busy: true });
    }

    #[tokio::test]
    async fn activity_after_slot_released_is_idle_and_keeps_count() {
        // Arrange
        let admission = admission(1, 0, 1);
        drop(admission.acquire(1).await.unwrap());

        // Act
        let activity = admission.activity();

        // Assert
        assert_eq!(
            activity,
            Activity {
                started: 1,
                busy: false
            }
        );
    }

    #[tokio::test(start_paused = true)]
    async fn slot_check_after_max_request_is_deadline_exceeded() {
        // Arrange
        let admission = Admission::new(1, policy(0, 100), 0);
        let slot = admission.acquire(1).await.unwrap();

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

    #[tokio::test]
    async fn acquire_estimate_over_budget_is_resource_exhausted_without_waiting() {
        // Arrange
        let admission = admission(1, 8, 10);

        // Act
        let rejected = admission.acquire(10 * MIB + 1).await;

        // Assert
        assert_eq!(rejected.unwrap_err().code(), tonic::Code::ResourceExhausted);
        assert_eq!(admission.waiting(), 0);
    }

    #[tokio::test]
    async fn acquire_without_memory_waits_without_taking_a_slot() {
        // Arrange: 예산 10 MiB 중 8 MiB를 쥔다. 슬롯은 2개다
        let admission = admission(2, 8, 10);
        let big = admission.acquire(8 * MIB).await.unwrap();

        // Act
        let waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire(4 * MIB).await.map(|_| ()) })
        };
        until_waiting(&admission, 1).await;

        // Assert: 메모리를 기다리는 요청은 슬롯을 쥐지 않는다. 메모리가 풀리면 처리된다
        assert_eq!(admission.permits.available_permits(), 1);
        drop(big);
        assert!(waiter.await.unwrap().is_ok());
        assert_eq!(admission.available_memory_bytes(), 10 * MIB);
    }

    #[tokio::test]
    async fn memory_waiters_count_toward_the_queue_limit() {
        // Arrange: 대기 상한 1. 메모리를 기다리는 요청 하나가 자리를 차지한다
        let admission = admission(2, 1, 10);
        let _big = admission.acquire(10 * MIB).await.unwrap();
        let _waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire(MIB).await.map(|_| ()) })
        };
        until_waiting(&admission, 1).await;

        // Act
        let rejected = admission.acquire(MIB).await;

        // Assert
        assert_eq!(rejected.unwrap_err().code(), tonic::Code::Unavailable);
    }

    #[tokio::test]
    async fn rejected_at_queue_limit_returns_memory_it_had_taken() {
        // Arrange: 슬롯 1개를 쥐고, 대기 상한(1건)을 채운다
        let admission = admission(1, 1, 10);
        let _held = admission.acquire(MIB).await.unwrap();
        let _waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire(MIB).await.map(|_| ()) })
        };
        until_waiting(&admission, 1).await;

        // Act: 메모리는 바로 잡히지만 슬롯이 없고 대기 자리도 없다
        let rejected = admission.acquire(MIB).await;

        // Assert: 쥔 1 MiB + 대기 중인 1 MiB만 예약돼 있다
        assert_eq!(rejected.unwrap_err().code(), tonic::Code::Unavailable);
        assert_eq!(admission.available_memory_bytes(), 8 * MIB);
    }

    #[tokio::test]
    async fn cancelled_while_waiting_for_memory_releases_queue_place() {
        // Arrange
        let admission = admission(1, 8, 10);
        let _big = admission.acquire(10 * MIB).await.unwrap();
        let waiter = {
            let a = admission.clone();
            tokio::spawn(async move { a.acquire(MIB).await.map(|_| ()) })
        };
        until_waiting(&admission, 1).await;

        // Act
        waiter.abort();
        let _ = waiter.await;

        // Assert
        assert_eq!(admission.waiting(), 0);
    }

    #[tokio::test]
    async fn ensure_reserved_beyond_free_budget_fails_without_waiting() {
        // Arrange: 두 요청이 예산을 나눠 쥔다
        let admission = admission(2, 8, 10);
        let mut first = admission.acquire(5 * MIB).await.unwrap();
        let _second = admission.acquire(5 * MIB).await.unwrap();

        // Act
        let topped = first.ensure_reserved(6 * MIB);

        // Assert
        assert_eq!(topped.unwrap_err().code(), tonic::Code::ResourceExhausted);
    }

    #[tokio::test]
    async fn ensure_reserved_adds_only_the_missing_amount() {
        // Arrange
        let admission = admission(1, 8, 10);
        let mut slot = admission.acquire(2 * MIB).await.unwrap();

        // Act
        slot.ensure_reserved(3 * MIB).unwrap();
        slot.ensure_reserved(MIB).unwrap();

        // Assert
        assert_eq!(admission.available_memory_bytes(), 7 * MIB);
    }

    #[tokio::test]
    async fn taken_memory_outlives_the_slot_until_dropped() {
        // Arrange
        let admission = admission(1, 8, 10);
        let mut slot = admission.acquire(4 * MIB).await.unwrap();
        let memory = slot.take_memory();

        // Act
        drop(slot);

        // Assert: 슬롯은 풀리고 예약은 남는다
        assert!(admission.acquire(MIB).await.is_ok());
        assert_eq!(admission.available_memory_bytes(), 6 * MIB);
        drop(memory);
        assert_eq!(admission.available_memory_bytes(), 10 * MIB);
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_stream_drops_queued_items_when_deadline_passes() {
        // Arrange: 읽히지 않은 항목이 채널에 남아 있다
        let (tx, rx) = mpsc::channel::<Result<u32, Status>>(4);
        tx.send(Ok(1)).await.unwrap();
        tx.send(Ok(2)).await.unwrap();
        let deadline = Instant::now() + Duration::from_millis(100);
        let mut stream = DeadlineStream::new(tokio_stream::wrappers::ReceiverStream::new(rx), deadline);
        let _ = tokio_stream::StreamExt::next(&mut stream).await;
        tokio::time::advance(Duration::from_millis(150)).await;

        // Act: 남은 항목과 끝을 읽는다
        let mut rest = Vec::new();
        while let Some(item) = tokio_stream::StreamExt::next(&mut stream).await {
            rest.push(item.map_err(|s| s.code()));
        }

        // Assert: 끝은 DEADLINE_EXCEEDED이고, 받는 쪽이 버려져 보내는 쪽이 닫힘을 본다
        assert_eq!(rest.last(), Some(&Err(tonic::Code::DeadlineExceeded)));
        assert!(tx.is_closed());
    }
}
