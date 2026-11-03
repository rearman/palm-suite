// links: assets draw msg text_edit records
// The test of desktop-rs/app/src/records.rs: the kept records come back only for their own text and kind.
#include "palm/records.hpp"

#include <doctest/doctest.h>

#include <vector>

namespace records = palm::records;
namespace model = palm::model;
namespace runner = palm::runner;

TEST_CASE("paste takes the kept records only for their own text and kind") {
	model::Memo m;
	m.text = "Groceries";
	const std::vector<runner::Record> want{runner::Record{m}};
	records::keep("memo", want, "Groceries");
	CHECK(records::records_for("memo", "Groceries") == want);
	CHECK_FALSE(records::records_for("memo", "Groceries\n").has_value());
	CHECK_FALSE(records::records_for("todo", "Groceries").has_value());
}
