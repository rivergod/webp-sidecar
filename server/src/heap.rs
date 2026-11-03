//! 유휴 서버의 힙 반환(ADR-0021).
//!
//! glibc 할당자는 해제한 블록을 arena에 남겨 다음 할당에 다시 쓴다. 부하 중에는 그래서 요청당 page fault가 거의 없지만,
//! 부하가 끝나도 상주 메모리가 돌아오지 않는다(research/0015: 유휴 60초 뒤 78–176 MiB). 서버가 유휴로 접어들면
//! `malloc_trim(0)`으로 arena의 빈 페이지를 운영체제에 돌려준다. glibc가 아닌 빌드(musl 등)에서는 아무것도 하지 않는다.

use crate::admission::Activity;

/// 정리 주기마다 trim할지 정한다. 정리 작업 하나가 소유해 쓴다(공유하지 않는다).
#[derive(Debug, Default)]
pub struct IdleTrim {
    /// 직전 주기에 본 누적 요청 수.
    seen_started: u64,
    /// 마지막 trim 때의 누적 요청 수.
    trimmed_started: u64,
}

impl IdleTrim {
    /// 이번 주기의 활동과, 이번 주기에 출력 버퍼 풀이 놓은 바이트 수로 trim할지 정한다.
    ///
    /// 직전 주기 뒤로 새 요청이 없고 지금 처리·대기 중인 요청이 없을 때만 참이다. 그중에서도 마지막 trim 뒤에 요청이
    /// 있었거나 풀이 버퍼를 놓았을 때만 참이다. 오래 쉬는 동안 같은 trim을 되풀이하지 않으려는 것이다.
    pub fn should_trim(&mut self, activity: Activity, pool_freed_bytes: u64) -> bool {
        let quiet = !activity.busy && activity.started == self.seen_started;
        let dirty = activity.started != self.trimmed_started || pool_freed_bytes > 0;
        self.seen_started = activity.started;
        if quiet && dirty {
            self.trimmed_started = activity.started;
        }
        quiet && dirty
    }
}

/// 할당자가 쥔 빈 페이지를 운영체제에 돌려준다. 무엇이든 돌려줬으면 참이다. glibc 빌드가 아니면 아무것도 하지 않고 거짓.
///
/// 모든 arena를 잠그고 훑으므로 부하 중에는 부르지 않는다([`IdleTrim`]).
pub fn release_free_memory() -> bool {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    {
        // SAFETY: malloc_trim은 남길 여유 바이트 수만 받고 포인터를 다루지 않는다. arena 잠금은 glibc가 잡는다.
        unsafe { libc::malloc_trim(0) != 0 }
    }
    #[cfg(not(all(target_os = "linux", target_env = "gnu")))]
    {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn idle(started: u64) -> Activity {
        Activity { started, busy: false }
    }

    /// 요청 `started`건을 처리한 뒤 한 주기를 쉬어 이미 한 번 trim한 상태.
    fn trimmed_after(started: u64) -> IdleTrim {
        let mut trim = IdleTrim::default();
        trim.should_trim(idle(started), 0);
        assert!(trim.should_trim(idle(started), 0));
        trim
    }

    #[test]
    fn should_trim_at_startup_without_requests_is_false() {
        // Arrange
        let mut trim = IdleTrim::default();

        // Act
        let decided = trim.should_trim(idle(0), 0);

        // Assert
        assert!(!decided);
    }

    #[test]
    fn should_trim_in_period_with_new_requests_is_false() {
        // Arrange
        let mut trim = IdleTrim::default();

        // Act
        let decided = trim.should_trim(idle(5), 0);

        // Assert
        assert!(!decided);
    }

    #[test]
    fn should_trim_after_quiet_period_following_requests_is_true() {
        // Arrange
        let mut trim = IdleTrim::default();
        trim.should_trim(idle(5), 0);

        // Act
        let decided = trim.should_trim(idle(5), 0);

        // Assert
        assert!(decided);
    }

    #[test]
    fn should_trim_while_request_in_flight_is_false() {
        // Arrange
        let mut trim = IdleTrim::default();
        trim.should_trim(idle(5), 0);

        // Act
        let decided = trim.should_trim(Activity { started: 5, busy: true }, 0);

        // Assert
        assert!(!decided);
    }

    #[test]
    fn should_trim_again_while_idle_without_changes_is_false() {
        // Arrange
        let mut trim = trimmed_after(5);

        // Act
        let decided = trim.should_trim(idle(5), 0);

        // Assert
        assert!(!decided);
    }

    #[test]
    fn should_trim_when_pool_releases_buffers_while_idle_is_true() {
        // Arrange
        let mut trim = trimmed_after(5);

        // Act
        let decided = trim.should_trim(idle(5), 48 * 1024 * 1024);

        // Assert
        assert!(decided);
    }

    #[test]
    fn should_trim_when_pool_releases_buffers_under_load_is_false() {
        // Arrange
        let mut trim = trimmed_after(5);

        // Act
        let decided = trim.should_trim(Activity { started: 9, busy: true }, 48 * 1024 * 1024);

        // Assert
        assert!(!decided);
    }
}
