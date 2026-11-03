// links: msg
// The channel of desktop-rs/app/src/msg.rs without a display: a worker thread sends, the Receiver gets the messages in
// order, and std::visit reads the variants. Fl::awake() runs with no display open, which only posts to no thread.
#include "palm/msg.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <optional>
#include <string>
#include <thread>
#include <type_traits>
#include <variant>

namespace msg = palm::msg;

TEST_CASE("a Sender on another thread sends, and the Receiver gets the messages in order") {
	auto [sender, receiver] = msg::channel();
	constexpr std::int64_t count = 1000;
	// recv() never blocks, so the test joins the worker before it reads the queue.
	std::thread worker([sender = sender] {
		for (std::int64_t i = 0; i < count; ++i) {
			sender.send(msg::ShowTodo{i});
		}
		sender.send(msg::Exit{});
	});
	worker.join();
	for (std::int64_t i = 0; i < count; ++i) {
		std::optional<msg::Msg> got = receiver.recv();
		REQUIRE(got.has_value());
		const auto* show = std::get_if<msg::ShowTodo>(&*got);
		REQUIRE(show != nullptr);
		CHECK(show->id == i);
	}
	std::optional<msg::Msg> last = receiver.recv();
	REQUIRE(last.has_value());
	CHECK(std::holds_alternative<msg::Exit>(*last));
	CHECK_FALSE(receiver.recv().has_value());
}

TEST_CASE("recv on an empty queue gives no value and does not block") {
	auto [sender, receiver] = msg::channel();
	CHECK_FALSE(receiver.recv().has_value());
	sender.send(msg::SaveAll{});
	CHECK(receiver.recv().has_value());
	CHECK_FALSE(receiver.recv().has_value());
}

TEST_CASE("std::visit reads two Msg variants with their fields") {
	const msg::Msg first = msg::ShowView{msg::View::Todo};
	const msg::Msg second = msg::Edit{msg::EditCmd::DeleteNoConfirm};

	const auto describe = [](const msg::Msg& m) -> std::string {
		return std::visit(
		    [](const auto& v) -> std::string {
			    using T = std::decay_t<decltype(v)>;
			    if constexpr (std::is_same_v<T, msg::ShowView>) {
				    return std::string(msg::key(v.view));
			    } else if constexpr (std::is_same_v<T, msg::Edit>) {
				    return v.cmd == msg::EditCmd::DeleteNoConfirm ? "delete-no-confirm" : "other";
			    } else {
				    return "unknown";
			    }
		    },
		    m);
	};
	CHECK(describe(first) == "todo");
	CHECK(describe(second) == "delete-no-confirm");
}

TEST_CASE("the view keys round-trip and an unknown key gives no view") {
	for (msg::View view : msg::ALL) {
		CHECK(msg::from_key(msg::key(view)) == view);
	}
	CHECK_FALSE(msg::from_key("calendar").has_value());
}
