#include "palm/logic.hpp"

#include <utility>

namespace palm::logic {

Action decide(LocalState local, bool remote_deleted, bool same, bool last_wins) {
	switch (local) {
	case LocalState::None:
		if (remote_deleted) {
			return Action::Skip;
		}
		return Action::Apply;
	case LocalState::Clean:
	case LocalState::Tomb:
		if (remote_deleted) {
			return Action::Drop;
		}
		return Action::Apply;
	case LocalState::Dirty:
		if (last_wins) {
			return Action::Rebase;
		}
		if (remote_deleted) {
			return Action::CopyThenDrop;
		}
		if (same) {
			return Action::Apply;
		}
		return Action::CopyThenApply;
	}
	std::unreachable();
}

} // namespace palm::logic
