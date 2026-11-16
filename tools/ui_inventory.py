# Lists the UI controls of the Android apps, and checks that INVENTORY.md has a row for each.
#
# Usage, from the repository root:
#   python3 tools/ui_inventory.py                     lists every control as TSV: file, line, function, kind, label
#   python3 tools/ui_inventory.py --check [INVENTORY]  lists the controls with no row in INVENTORY.md (or the
#                                                      file named), and exits 1 if any
#
# A row covers a control when its Kind cell is the control's kind, its Code cell names the function
# that holds the control, and one of the labels of its Item cell (see item_labels) equals the
# control's label. Limit: it reads the source with regular expressions, so a control built through
# a new helper is missed until the helper gets a pattern below.
import bisect
import re
import sys
from pathlib import Path

# The source folders of the Android apps.
ROOTS = ["datebook/app/src/main", "palm-launcher/app/src/main", "palm-ui/src/main"]

# A label argument: a string literal, or an if expression that picks one of two literals.
# Limit: the condition may nest one level of parentheses; a deeper one is missed.
LIT = r'("[^"]*"|if\s*\((?:[^()]|\([^()]*\))*\)\s*"[^"]*"\s*else\s*"[^"]*")'

# One pattern for each kind of control. Group 1 is the label. The patterns run on the whole file,
# so \s matches a line break and an argument may start on the next line.
PATTERNS = [
	("screen", re.compile(r'\btab\(\w+,\s*("[^"]*"|[^,)]+)')),
	("dialog", re.compile(r'PalmDialog\((?:\w+),\s*(' + LIT + r'|\w+)')),
	("alert", re.compile(r'palmAlert\(\s*' + LIT)),
	("alert", re.compile(r'\bask\(\s*' + LIT)),
	("button", re.compile(r'\bcmd\(\w+,\s*' + LIT)),
	("button", re.compile(r'\bbutton\(\s*' + LIT)),
	("button", re.compile(r'palmButton\(\s*' + LIT)),
	("menu", re.compile(r'MenuItem\(\s*' + LIT)),
	("trigger", re.compile(r'popupTrigger\(\s*([^)]{0,60})')),
	("check", re.compile(r'checkBox\(\s*' + LIT)),
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
# A state mark that a menu command puts before its label, such as the check of "✓ Wallpaper".
MARK = re.compile(r'^"✓\s*')


# Returns the context of each line of a file (index 0 is line 1): the top-level function, class,
# or class member that holds it, and the innermost local function with a block body inside that,
# or None.
def contexts(lines):
	out = []
	ctx = "(top)"
	depth = 0
	# Brace depths of the bodies of the classes open at this line.
	classes = []
	# Names and body depths of the local functions open at this line.
	local = []
	for line in lines:
		while classes and depth < classes[-1]:
			classes.pop()
		while local and depth < local[-1][1]:
			local.pop()
		m = HEADER.match(line)
		if m and (depth == 0 or (classes and depth == classes[-1])):
			# A top-level function, a class, or a class member sets the context.
			ctx = m.group(2).split(".")[-1]
			local = []
			if m.group(1) == "class":
				classes.append(depth + 1)
		elif m and m.group(1) == "fun" and "{" in line:
			# A local function inside a function or an init block: its controls belong to it.
			# Limit: an expression body, or a brace on the next line, is not tracked.
			local.append((m.group(2), depth + 1))
		out.append((ctx, local[-1][0] if local else None))
		# Naive brace count: braces inside string literals are rare in this code and balanced.
		depth += line.count("{") - line.count("}")
	return out


# Returns the labels a matched argument gives: both literals of an if expression, else the argument.
def labels(arg):
	arg = " ".join(arg.split())
	if arg.startswith("if"):
		found = re.findall(r'"[^"]*"', arg)
		return [MARK.sub('"', s) for s in found[-2:]]
	return [MARK.sub('"', arg)]


# Yields (file, line, function, local function or None, kind, label) for each control in the source.
def controls(repo):
	for root in ROOTS:
		for path in sorted((repo / root).rglob("*.kt")):
			text = path.read_text(encoding="utf-8")
			lines = text.split("\n")
			ctxs = contexts(lines)
			# The offset of the start of each line, to turn a match offset into a line number.
			starts = [0]
			for line in lines[:-1]:
				starts.append(starts[-1] + len(line) + 1)
			hits = []
			for kind, pat in PATTERNS:
				for hit in pat.finditer(text):
					n = bisect.bisect_right(starts, hit.start())
					for label in labels(hit.group(1).strip()):
						hits.append((n, kind, label))
			for n, kind, label in sorted(hits, key=lambda h: h[0]):
				ctx, local = ctxs[n - 1]
				yield path.name, n, ctx, local, kind, label


# Returns the labels an Item cell names. A quoted title gives its quoted texts. Else the item, the
# item with its parenthesized asides removed, the text before its first ": " (the rest describes
# it), and for a menu the commands after the last ": " (the text before names the menu), split at
# " / " and ", ".
def item_labels(kind, item):
	if item.startswith('"'):
		return set(re.findall(r'"([^"]*)"', item))
	bare = re.sub(r"\s*\([^)]*\)", "", item).strip()
	out = {item, bare, bare.split(": ", 1)[0]}
	if kind == "menu":
		out.update(s.strip() for s in re.split(r" / |, ", bare.rsplit(": ", 1)[-1]))
	return out


# Returns the (kind, item, code) cells of the rows of every table in the inventory.
def rows(text):
	out = []
	for line in text.splitlines():
		cells = [c.strip() for c in line.strip().strip("|").split("|")]
		if line.lstrip().startswith("|") and len(cells) >= 3 and not set(cells[0]) <= set("-: "):
			out.append((cells[0], cells[1], cells[2]))
	return out


def main():
	repo = Path(__file__).resolve().parent.parent
	args = sys.argv[1:]
	if "--check" not in args:
		for name, n, ctx, local, kind, label in controls(repo):
			where = f"{ctx}.{local}" if local else ctx
			print(f"{name}\t{n}\t{where}\t{kind}\t{label}")
		return 0
	# An optional path after --check names the inventory to check, e.g. a scratch copy.
	rest = [a for a in args if a != "--check"]
	inventory = Path(rest[0]) if rest else repo / "INVENTORY.md"
	table = [(kind, item_labels(kind, item), re.findall(r"\w+", code)) for kind, item, code in rows(inventory.read_text(encoding="utf-8"))]
	missing = []
	for name, n, ctx, local, kind, label in controls(repo):
		text = label.strip('"')
		if kind not in CHECKED or not label.startswith('"') or not text:
			continue
		# A control in a local function needs a row whose Code cell names that local function.
		owner = local or ctx
		if not any(k == kind and owner in code and text in names for k, names, code in table):
			missing.append(f"{name}:{n}\t{owner}\t{kind}\t{text}")
	for m in missing:
		print(m)
	print(f"{len(missing)} controls have no row in INVENTORY.md", file=sys.stderr)
	return 1 if missing else 0


sys.exit(main())
