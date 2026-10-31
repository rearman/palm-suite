# Lists the UI controls of the Android apps, and checks that INVENTORY.md has a row for each.
#
# Usage, from the repository root:
#   python3 tools/ui_inventory.py          lists every control as TSV: file, line, function, kind, label
#   python3 tools/ui_inventory.py --check  lists the controls with no row in INVENTORY.md, and exits 1 if any
#
# A row covers a control when its Code cell names the function that holds the control and its
# Item cell holds the control's label. Limit: it reads the source with regular expressions, so a
# control built through a new helper is missed until the helper gets a pattern below.
import re
import sys
from pathlib import Path

# The source folders of the Android apps.
ROOTS = ["datebook/app/src/main", "palm-launcher/app/src/main", "palm-ui/src/main"]

# One pattern for each kind of control. Group 1 is the label.
PATTERNS = [
	("screen", re.compile(r'\btab\(\w+,\s*("[^"]*"|[^,)]+)')),
	("dialog", re.compile(r'PalmDialog\((?:\w+),\s*("[^"]*"|\w+)')),
	("alert", re.compile(r'palmAlert\(\s*("[^"]*")')),
	("alert", re.compile(r'\bask\(\s*("[^"]*")')),
	("button", re.compile(r'\bcmd\(\w+,\s*("[^"]*")')),
	("button", re.compile(r'\bbutton\(\s*("[^"]*")')),
	("button", re.compile(r'palmButton\(\s*("[^"]*")')),
	("menu", re.compile(r'MenuItem\(\s*("[^"]*")')),
	("trigger", re.compile(r'popupTrigger\(\s*([^)]{0,60})')),
	("check", re.compile(r'checkBox\(\s*("[^"]*")')),
	("push", re.compile(r'PushGroup\(\w+,\s*(listOf\([^)]*\)|[^,]+)')),
	("icon", re.compile(r'IconView\(\w+(?:@\w+)?,\s*IconView\.(\w+)')),
	("icon", re.compile(r'ViewIcon\(\w+,\s*(\w+)')),
	("toast", re.compile(r'\btoast\(\s*("[^"]*")')),
	("field", re.compile(r'\bfield\(\s*("[^"]*"|[^,)]+)')),
]
# The kinds whose label is fixed text, which --check can look for.
CHECKED = {"dialog", "alert", "button", "menu", "check"}
# A function or class header.
HEADER = re.compile(r'^\s*(?:(?:private|internal|override|open|protected|abstract)\s+)*(fun|class)\s+(?:<[^>]*>\s*)?([\w.]+)')


# Yields (file, line, function, kind, label) for each control in the source.
def controls(repo):
	for root in ROOTS:
		for path in sorted((repo / root).rglob("*.kt")):
			ctx = "(top)"
			depth = 0
			# Brace depths of the bodies of the classes open at this line.
			classes = []
			for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
				while classes and depth < classes[-1]:
					classes.pop()
				m = HEADER.match(line)
				# A top-level function, a class, or a class member sets the context. A local function
				# inside a function or an init block keeps the outer context.
				if m and (depth == 0 or (classes and depth == classes[-1])):
					ctx = m.group(2).split(".")[-1]
					if m.group(1) == "class":
						classes.append(depth + 1)
				for kind, pat in PATTERNS:
					for hit in pat.finditer(line):
						yield path.name, n, ctx, kind, hit.group(1).strip()
				# Naive brace count: braces inside string literals are rare in this code and balanced.
				depth += line.count("{") - line.count("}")


# Returns the (code, item) cells of the rows of every table in the inventory.
def rows(text):
	out = []
	for line in text.splitlines():
		cells = [c.strip() for c in line.strip().strip("|").split("|")]
		if line.lstrip().startswith("|") and len(cells) >= 3 and not set(cells[0]) <= set("-: "):
			out.append((cells[2], cells[1]))
	return out


def main():
	repo = Path(__file__).resolve().parent.parent
	if "--check" not in sys.argv:
		for c in controls(repo):
			print("\t".join(map(str, c)))
		return 0
	table = rows((repo / "INVENTORY.md").read_text(encoding="utf-8"))
	missing = []
	for name, n, ctx, kind, label in controls(repo):
		text = label.strip('"')
		if kind not in CHECKED or not label.startswith('"') or not text:
			continue
		if not any(ctx in re.findall(r"\w+", code) and text in item for code, item in table):
			missing.append(f"{name}:{n}\t{ctx}\t{kind}\t{text}")
	for m in missing:
		print(m)
	print(f"{len(missing)} controls have no row in INVENTORY.md", file=sys.stderr)
	return 1 if missing else 0


sys.exit(main())
