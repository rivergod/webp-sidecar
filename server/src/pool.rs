//! 정지 이미지 디코딩 출력 버퍼 풀(ADR-0018).
//!
//! musl 할당자는 큰 블록을 매번 mmap/munmap하므로 요청마다 새 출력 버퍼의 페이지 폴트와 0 채우기가 생긴다.
//! 쓴 버퍼를 총량 상한과 유휴 만료를 두고 다시 쓴다. 버퍼는 응답 전송이 끝나 마지막 조각이 해제될 때 돌아온다
//! ([`BufferPool::wrap`]). 스레드 안전하다(복제본은 같은 풀을 공유한다).

use bytes::Bytes;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// 풀 설정(ADR-0018).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PoolConfig {
    /// 보관할 버퍼 용량 합계의 상한. 0이면 풀을 끈다. 단위: 바이트.
    pub max_bytes: u64,
    /// 돌아온 뒤 이 시간 동안 다시 쓰이지 않은 버퍼는 해제한다.
    pub idle: Duration,
}

impl Default for PoolConfig {
    /// ADR-0018 기본값: 128 MiB, 30초.
    fn default() -> Self {
        Self {
            max_bytes: 128 * 1024 * 1024,
            idle: Duration::from_secs(30),
        }
    }
}

/// 출력 버퍼 풀. 복제하면 같은 풀을 가리킨다.
#[derive(Debug, Clone)]
pub struct BufferPool {
    inner: Arc<Inner>,
}

#[derive(Debug)]
struct Inner {
    config: PoolConfig,
    state: Mutex<State>,
}

#[derive(Debug, Default)]
struct State {
    buffers: Vec<Entry>,
    bytes: u64,
}

#[derive(Debug)]
struct Entry {
    buf: Vec<u8>,
    returned: Instant,
}

impl BufferPool {
    /// 설정으로 풀을 만든다. `max_bytes`가 0이면 아무것도 보관하지 않는다.
    pub fn new(config: PoolConfig) -> Self {
        Self {
            inner: Arc::new(Inner {
                config,
                state: Mutex::new(State::default()),
            }),
        }
    }

    /// 설정.
    pub fn config(&self) -> PoolConfig {
        self.inner.config
    }

    fn state(&self) -> MutexGuard<'_, State> {
        // 잠금 중 패닉이 나도 상태(버퍼 목록과 합계)는 일관되므로 그대로 쓴다.
        self.inner.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// 용량이 `min_capacity` 바이트 이상인 버퍼 중 가장 작은 것을 꺼낸다. 없으면 빈 `Vec`(호출자가 키운다).
    /// 돌려주는 버퍼의 길이는 0이다(이전 내용은 용량 안에만 남고 보이지 않는다).
    pub fn take(&self, min_capacity: usize) -> Vec<u8> {
        if self.inner.config.max_bytes == 0 {
            return Vec::new();
        }
        let expired;
        let found = {
            let mut st = self.state();
            expired = st.remove_expired(Instant::now(), self.inner.config.idle);
            let best = st
                .buffers
                .iter()
                .enumerate()
                .filter(|(_, e)| e.buf.capacity() >= min_capacity)
                .min_by_key(|(_, e)| e.buf.capacity())
                .map(|(i, _)| i);
            best.map(|i| {
                let e = st.buffers.swap_remove(i);
                st.bytes -= e.buf.capacity() as u64;
                e.buf
            })
        };
        // 해제(munmap)는 잠금 밖에서 한다.
        drop(expired);
        found.unwrap_or_default()
    }

    /// 버퍼를 돌려놓는다. 풀이 꺼졌거나 버퍼 하나가 총량 상한보다 크면 해제한다. 총량을 넘으면 가장 오래 쓰지 않은
    /// 버퍼부터 해제한다.
    pub fn give(&self, mut buf: Vec<u8>) {
        let capacity = buf.capacity() as u64;
        let max = self.inner.config.max_bytes;
        if capacity == 0 || capacity > max {
            return;
        }
        buf.clear();
        let now = Instant::now();
        let mut released = {
            let mut st = self.state();
            let mut released = st.remove_expired(now, self.inner.config.idle);
            st.bytes += capacity;
            st.buffers.push(Entry { buf, returned: now });
            while st.bytes > max {
                let oldest = st
                    .buffers
                    .iter()
                    .enumerate()
                    .min_by_key(|(_, e)| e.returned)
                    .map(|(i, _)| i)
                    .expect("bytes > 0 implies a buffer");
                let e = st.buffers.swap_remove(oldest);
                st.bytes -= e.buf.capacity() as u64;
                released.push(e.buf);
            }
            released
        };
        released.clear();
    }

    /// 유휴 만료가 지난 버퍼를 해제하고, 해제한 용량 합계를 돌려준다. 주기적으로 부른다.
    pub fn evict_expired(&self) -> u64 {
        let expired = self.state().remove_expired(Instant::now(), self.inner.config.idle);
        expired.iter().map(|b| b.capacity() as u64).sum()
    }

    /// 지금 보관 중인 버퍼 용량 합계. 단위: 바이트.
    pub fn pooled_bytes(&self) -> u64 {
        self.state().bytes
    }

    /// 지금 보관 중인 버퍼 수.
    pub fn pooled_buffers(&self) -> usize {
        self.state().buffers.len()
    }

    /// 버퍼를 [`Bytes`]로 감싼다. 이 `Bytes`와 그 조각(`slice`)이 모두 해제되면 버퍼가 풀로 돌아온다.
    pub fn wrap(&self, buf: Vec<u8>) -> Bytes {
        Bytes::from_owner(Pooled {
            buf,
            pool: self.clone(),
        })
    }
}

impl State {
    fn remove_expired(&mut self, now: Instant, idle: Duration) -> Vec<Vec<u8>> {
        let mut expired = Vec::new();
        let mut i = 0;
        while i < self.buffers.len() {
            if now.duration_since(self.buffers[i].returned) >= idle {
                let e = self.buffers.swap_remove(i);
                self.bytes -= e.buf.capacity() as u64;
                expired.push(e.buf);
            } else {
                i += 1;
            }
        }
        expired
    }
}

/// [`BufferPool::wrap`]의 소유자. 해제될 때 버퍼를 풀로 돌려놓는다.
struct Pooled {
    buf: Vec<u8>,
    pool: BufferPool,
}

impl AsRef<[u8]> for Pooled {
    fn as_ref(&self) -> &[u8] {
        &self.buf
    }
}

impl Drop for Pooled {
    fn drop(&mut self) {
        self.pool.give(std::mem::take(&mut self.buf));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pool(max_bytes: u64, idle_ms: u64) -> BufferPool {
        BufferPool::new(PoolConfig {
            max_bytes,
            idle: Duration::from_millis(idle_ms),
        })
    }

    fn buffer(capacity: usize) -> Vec<u8> {
        Vec::with_capacity(capacity)
    }

    #[test]
    fn take_picks_smallest_sufficient_buffer() {
        // Arrange
        let p = pool(1 << 20, 60_000);
        p.give(buffer(1000));
        p.give(buffer(300));
        p.give(buffer(500));

        // Act
        let b = p.take(400);

        // Assert
        assert_eq!(b.capacity(), 500);
        assert_eq!(b.len(), 0);
        assert_eq!(p.pooled_buffers(), 2);
    }

    #[test]
    fn take_without_sufficient_buffer_returns_empty_vec() {
        // Arrange
        let p = pool(1 << 20, 60_000);
        p.give(buffer(100));

        // Act
        let b = p.take(200);

        // Assert
        assert_eq!(b.capacity(), 0);
        assert_eq!(p.pooled_buffers(), 1);
    }

    #[test]
    fn give_over_total_limit_evicts_least_recently_returned() {
        // Arrange
        let p = pool(1000, 60_000);
        p.give(buffer(400));
        std::thread::sleep(Duration::from_millis(2));
        p.give(buffer(500));

        // Act
        p.give(buffer(300));

        // Assert: 400(가장 오래됨)을 버려 800 ≤ 1000
        assert_eq!(p.pooled_bytes(), 800);
        assert_eq!(p.take(450).capacity(), 500);
        assert_eq!(p.take(250).capacity(), 300);
    }

    #[test]
    fn give_buffer_larger_than_total_limit_is_not_kept() {
        // Arrange
        let p = pool(1000, 60_000);

        // Act
        p.give(buffer(2000));

        // Assert
        assert_eq!(p.pooled_buffers(), 0);
    }

    #[test]
    fn disabled_pool_keeps_nothing() {
        // Arrange
        let p = pool(0, 60_000);

        // Act
        p.give(buffer(100));

        // Assert
        assert_eq!(p.pooled_buffers(), 0);
        assert_eq!(p.take(1).capacity(), 0);
    }

    #[test]
    fn evict_expired_releases_idle_buffers() {
        // Arrange
        let p = pool(1 << 20, 5);
        p.give(buffer(100));
        std::thread::sleep(Duration::from_millis(10));

        // Act
        let freed = p.evict_expired();

        // Assert
        assert_eq!(freed, 100);
        assert_eq!(p.pooled_bytes(), 0);
    }

    #[test]
    fn wrapped_bytes_return_buffer_after_last_slice_drops() {
        // Arrange
        let p = pool(1 << 20, 60_000);
        let mut v = buffer(64);
        v.extend_from_slice(&[7u8; 64]);
        let bytes = p.wrap(v);
        let slice = bytes.slice(0..32);
        drop(bytes);
        assert_eq!(p.pooled_buffers(), 0);

        // Act
        drop(slice);

        // Assert
        assert_eq!(p.pooled_buffers(), 1);
        assert_eq!(p.take(64).len(), 0);
    }
}
