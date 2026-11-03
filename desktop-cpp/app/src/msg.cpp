#include "palm/msg.hpp"

#include <FL/Fl.H>

#include <deque>
#include <mutex>

namespace palm::msg {

struct Queue {
	std::mutex mutex;
	std::deque<Msg> items;
};

std::string_view key(View view) {
	switch (view) {
	case View::Date:
		return "date";
	case View::Address:
		return "address";
	case View::Todo:
		return "todo";
	case View::Memo:
		return "memo";
	}
	return "";
}

std::optional<View> from_key(std::string_view name) {
	for (View view : ALL) {
		if (key(view) == name) {
			return view;
		}
	}
	return std::nullopt;
}

std::string_view key(DateMode mode) {
	switch (mode) {
	case DateMode::Day:
		return "day";
	case DateMode::Week:
		return "week";
	case DateMode::Month:
		return "month";
	}
	return "";
}

Sender::Sender(std::shared_ptr<Queue> queue) : queue(std::move(queue)) {}

void Sender::send(Msg msg) const {
	{
		std::lock_guard lock(queue->mutex);
		queue->items.push_back(std::move(msg));
	}
	// Fl::awake() is thread-safe: it posts a wake-up to the main thread, which then calls recv().
	Fl::awake();
}

Receiver::Receiver(std::shared_ptr<Queue> queue) : queue(std::move(queue)) {}

std::optional<Msg> Receiver::recv() {
	std::lock_guard lock(queue->mutex);
	if (queue->items.empty()) {
		return std::nullopt;
	}
	Msg msg = std::move(queue->items.front());
	queue->items.pop_front();
	return msg;
}

std::pair<Sender, Receiver> channel() {
	auto queue = std::make_shared<Queue>();
	return {Sender(queue), Receiver(queue)};
}

} // namespace palm::msg
