//! The lenient reads of org.json (optString, optInt, optLong, optBoolean, optJSONArray) over serde_json
//! values, so a record that the Kotlin apps read the same way reads the same here.

use serde_json::{Map, Value};

pub type Obj = Map<String, Value>;

/// org.json's text of a non-string value: numbers and booleans as written, objects and arrays as JSON.
fn text_of(v: &Value) -> String {
	match v {
		Value::String(s) => s.clone(),
		other => other.to_string(),
	}
}

/// org.json's number reading of a string: an integer, else a decimal truncated toward zero.
fn number_of_str(s: &str) -> Option<i64> {
	s.parse::<i64>()
		.ok()
		.or_else(|| s.parse::<f64>().ok().filter(|f| f.is_finite()).map(|f| f as i64))
}

fn long_of(v: &Value) -> Option<i64> {
	match v {
		Value::Number(n) => n
			.as_i64()
			.or_else(|| n.as_u64().map(|u| u as i64))
			.or_else(|| n.as_f64().map(|f| f as i64)),
		Value::String(s) => number_of_str(s),
		_ => None,
	}
}

fn bool_of(v: &Value) -> Option<bool> {
	match v {
		Value::Bool(b) => Some(*b),
		Value::String(s) if s.eq_ignore_ascii_case("true") => Some(true),
		Value::String(s) if s.eq_ignore_ascii_case("false") => Some(false),
		_ => None,
	}
}

/// optString(key): "" when the key is missing or null.
pub fn opt_str(o: &Obj, key: &str) -> String {
	opt_str_or(o, key, "")
}

pub fn opt_str_or(o: &Obj, key: &str, default: &str) -> String {
	match o.get(key) {
		None | Some(Value::Null) => default.to_string(),
		Some(v) => text_of(v),
	}
}

/// optInt(key, default). A long value keeps its low 32 bits, as Java's intValue.
pub fn opt_i32(o: &Obj, key: &str, default: i32) -> i32 {
	o.get(key).and_then(long_of).map(|l| l as i32).unwrap_or(default)
}

pub fn opt_i64(o: &Obj, key: &str, default: i64) -> i64 {
	o.get(key).and_then(long_of).unwrap_or(default)
}

pub fn opt_bool(o: &Obj, key: &str) -> bool {
	o.get(key).and_then(bool_of).unwrap_or(false)
}

pub fn opt_array<'a>(o: &'a Obj, key: &str) -> Option<&'a Vec<Value>> {
	o.get(key).and_then(Value::as_array)
}

/// JSONArray.optInt(i, default).
pub fn at_i32(a: &[Value], i: usize, default: i32) -> i32 {
	a.get(i).and_then(long_of).map(|l| l as i32).unwrap_or(default)
}

/// JSONArray.optLong(i): 0 when missing.
pub fn at_i64(a: &[Value], i: usize) -> i64 {
	a.get(i).and_then(long_of).unwrap_or(0)
}

/// JSONArray.optString(i, default).
pub fn at_str(a: &[Value], i: usize, default: &str) -> String {
	match a.get(i) {
		None | Some(Value::Null) => default.to_string(),
		Some(v) => text_of(v),
	}
}

/// getLong(key): a number or a numeric string, else an error.
pub fn get_i64(o: &Obj, key: &str) -> Result<i64, String> {
	o.get(key)
		.and_then(long_of)
		.ok_or_else(|| format!("JSONObject[\"{key}\"] is not a number."))
}

/// getString(key): a string, else an error.
pub fn get_str(o: &Obj, key: &str) -> Result<String, String> {
	match o.get(key) {
		Some(Value::String(s)) => Ok(s.clone()),
		_ => Err(format!("JSONObject[\"{key}\"] is not a string.")),
	}
}

/// The JSON object of a text, or an error when the text is not one.
pub fn parse_obj(text: &str) -> Result<Obj, String> {
	match serde_json::from_str::<Value>(text) {
		Ok(Value::Object(o)) => Ok(o),
		Ok(_) => Err("A JSONObject text must begin with '{'.".to_string()),
		Err(e) => Err(e.to_string()),
	}
}
