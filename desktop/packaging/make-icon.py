#!/usr/bin/env python3
"""Draw the Palm Desktop app icon and write its PNG and ICO files.

The icon follows the Android launcher icons of Date Book (datebook/app/src/main/res/drawable/ic_launcher_fg.xml):
black line art on a white page, 3 px strokes in a 108 x 108 grid. It shows a monitor with a page of calendar
on the screen, and a rounded square behind it. The rounded square has a gray edge that lies inside the shape,
so the area outside it stays transparent.

Each size is drawn on a grid four times finer than the output (4 * S by 4 * S), as coverage masks, and then
averaged in 4 by 4 blocks down to S by S. The output is straight RGBA, 8 bits per channel.

Writes:
	desktop/packaging/palm-desktop.ico                              (16, 32, 48 and 256 px, PNG entries)
	desktop/packaging/palm-desktop.png                              (256 px)
	desktop/src/main/resources/com/rearman/palmdesktop/icons/app16.png
	desktop/src/main/resources/com/rearman/palmdesktop/icons/app32.png
	desktop/src/main/resources/com/rearman/palmdesktop/icons/app48.png
	desktop/src/main/resources/com/rearman/palmdesktop/icons/app256.png

Run from the repository root:

	python desktop/packaging/make-icon.py

The script ends with a self-check. It prints "icons ok" when every check passes.
Needs Python 3 with numpy. It uses no other package.
"""

import os
import struct
import zlib

import numpy as np

GRID = 108.0
SS = 4  # supersampling factor per output pixel
PNG_SIG = b"\x89PNG\r\n\x1a\n"
EDGE_GRAY = 0x90 / 255.0

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
ICO_SIZES = (16, 32, 48, 256)
RES_DIR = os.path.join(ROOT, "desktop", "src", "main", "resources", "com", "rearman", "palmdesktop", "icons")


def axis_coords(n):
	"""Centers of the n sub-pixels on one axis, in 108-grid units, as a 1-D array."""
	return (np.arange(n) + 0.5) * (GRID / n)


def rect_mask(gx, gy, x0, y0, x1, y1):
	"""Binary mask of a filled rectangle. gx is a row vector and gy a column vector."""
	return ((gx >= x0) & (gx <= x1) & (gy >= y0) & (gy <= y1)).astype(float)


def stroke_rect(gx, gy, x0, y0, x1, y1, w):
	"""Mask of a stroked rectangle. The stroke is centered on the given edges."""
	h = w / 2.0
	outer = rect_mask(gx, gy, x0 - h, y0 - h, x1 + h, y1 + h)
	inner = rect_mask(gx, gy, x0 + h, y0 + h, x1 - h, y1 - h)
	return np.clip(outer - inner, 0.0, 1.0)


def rounded_sd(gx, gy, x0, y0, x1, y1, r):
	"""Signed distance to a rounded rectangle, negative inside, in grid units."""
	cx = (x0 + x1) / 2.0
	cy = (y0 + y1) / 2.0
	hw = (x1 - x0) / 2.0 - r
	hh = (y1 - y0) / 2.0 - r
	dx = np.abs(gx - cx) - hw
	dy = np.abs(gy - cy) - hh
	outside = np.hypot(np.maximum(dx, 0.0), np.maximum(dy, 0.0))
	inside = np.minimum(np.maximum(dx, dy), 0.0)
	return outside + inside - r


def render_masks(size):
	"""Return the (fill, edge, black) coverage masks on the supersampled grid for one output size."""
	n = size * SS
	c = axis_coords(n)
	gx = c[np.newaxis, :]
	gy = c[:, np.newaxis]

	sd = rounded_sd(gx, gy, 6.0, 6.0, 102.0, 102.0, 18.0)
	fill = (sd <= 0).astype(float)
	edge = ((sd <= 0) & (sd >= -1.5)).astype(float)

	black = np.zeros((n, n))
	# Monitor: stroked frame, neck and base.
	black = np.maximum(black, stroke_rect(gx, gy, 20, 24, 88, 74, 3))
	black = np.maximum(black, rect_mask(gx, gy, 51, 74, 57, 82))
	black = np.maximum(black, rect_mask(gx, gy, 38, 82, 70, 86))
	# Calendar page on the screen.
	black = np.maximum(black, stroke_rect(gx, gy, 34, 34, 74, 68, 3))
	black = np.maximum(black, rect_mask(gx, gy, 34, 34, 74, 42))
	for y in (51, 60):
		black = np.maximum(black, rect_mask(gx, gy, 34, y - 0.75, 74, y + 0.75))
	for x in (44, 54, 64):
		black = np.maximum(black, rect_mask(gx, gy, x - 0.75, 42, x + 0.75, 68))
	black = np.maximum(black, rect_mask(gx, gy, 54, 51, 64, 60))
	black = np.maximum(black, rect_mask(gx, gy, 40, 30, 43, 38))
	black = np.maximum(black, rect_mask(gx, gy, 65, 30, 68, 38))
	return fill, edge, black


def render(size):
	"""Return the icon at one size as a straight RGBA uint8 array of shape (size, size, 4)."""
	fill, edge, black = render_masks(size)
	n = size * SS
	rgb = np.ones((n, n, 3))
	rgb[edge > 0] = EDGE_GRAY
	alpha = fill.copy()
	rgb = rgb * (1.0 - black)[:, :, np.newaxis]
	alpha = np.maximum(alpha, black)

	prem = (rgb * alpha[:, :, np.newaxis]).reshape(size, SS, size, SS, 3).mean(axis=(1, 3))
	a = alpha.reshape(size, SS, size, SS).mean(axis=(1, 3))
	safe = np.where(a > 0, a, 1.0)
	straight = np.where(a[:, :, np.newaxis] > 0, prem / safe[:, :, np.newaxis], 0.0)

	out = np.zeros((size, size, 4), dtype=np.uint8)
	out[:, :, :3] = np.clip(np.rint(straight * 255.0), 0, 255).astype(np.uint8)
	out[:, :, 3] = np.clip(np.rint(a * 255.0), 0, 255).astype(np.uint8)
	return out


def chunk(kind, data):
	body = kind + data
	return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def png_bytes(rgba):
	h, w = rgba.shape[0], rgba.shape[1]
	raw = b"".join(b"\x00" + rgba[y].tobytes() for y in range(h))
	ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
	return PNG_SIG + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def ico_bytes(images):
	"""images is a list of (size, png_data). Each entry stores PNG data."""
	header = struct.pack("<HHH", 0, 1, len(images))
	entries = b""
	blobs = b""
	offset = 6 + 16 * len(images)
	for size, data in images:
		wb = 0 if size == 256 else size
		entries += struct.pack("<BBBBHHII", wb, wb, 0, 0, 1, 32, len(data), offset)
		offset += len(data)
		blobs += data
	return header + entries + blobs


def read_png_header(data):
	"""Return (width, height, color type) from a PNG byte string, checking the signature."""
	assert data[:8] == PNG_SIG, "not a PNG"
	length = struct.unpack(">I", data[8:12])[0]
	assert data[12:16] == b"IHDR" and length == 13, "IHDR missing"
	w, h, depth, ctype = struct.unpack(">IIBB", data[16:26])
	assert depth == 8, "bit depth must be 8"
	return w, h, ctype


def read_ico(data):
	"""Return a list of (width, height, size, offset) for each ICO entry."""
	reserved, kind, count = struct.unpack("<HHH", data[:6])
	assert reserved == 0 and kind == 1, "ICO header"
	entries = []
	for i in range(count):
		base = 6 + 16 * i
		wb, hb, _, _, planes, bits, size, offset = struct.unpack("<BBBBHHII", data[base:base + 16])
		w = 256 if wb == 0 else wb
		h = 256 if hb == 0 else hb
		assert planes == 1 and bits == 32, "planes or bit count"
		entries.append((w, h, size, offset))
	return entries


def self_check(images, ico, rendered):
	for size in (16, 32, 48, 256):
		arr = rendered[size]
		assert arr.shape == (size, size, 4), "rendered size %d" % size
		w, h, ctype = read_png_header(images[size])
		assert (w, h, ctype) == (size, size, 6), "PNG header %d" % size

	big = rendered[256]
	px, py = int(30 * 256 / GRID), int(28 * 256 / GRID)
	assert tuple(big[py, px]) == (255, 255, 255, 255), "gap pixel should be white and opaque"
	assert big[0, 0, 3] == 0, "corner pixel should be transparent"
	mx, my = int(20 * 256 / GRID), int(50 * 256 / GRID)
	assert tuple(big[my, mx, :3]) == (0, 0, 0) and big[my, mx, 3] == 255, "monitor stroke should be black"

	entries = read_ico(ico)
	assert [e[0] for e in entries] == [16, 32, 48, 256], "ICO sizes"
	assert [e[1] for e in entries] == [16, 32, 48, 256], "ICO heights"
	for w, h, size, offset in entries:
		assert offset >= 6 + 16 * len(entries), "ICO offset before directory end"
		assert offset + size <= len(ico), "ICO entry past end of file"
		assert ico[offset:offset + 8] == PNG_SIG, "ICO entry is not PNG"


def write(path, data):
	os.makedirs(os.path.dirname(path), exist_ok=True)
	with open(path, "wb") as f:
		f.write(data)


def main():
	rendered = {s: render(s) for s in ICO_SIZES}
	images = {s: png_bytes(rendered[s]) for s in ICO_SIZES}
	ico = ico_bytes([(s, images[s]) for s in ICO_SIZES])

	self_check(images, ico, rendered)

	write(os.path.join(ROOT, "desktop", "packaging", "palm-desktop.ico"), ico)
	write(os.path.join(ROOT, "desktop", "packaging", "palm-desktop.png"), images[256])
	for s in (16, 32, 48, 256):
		write(os.path.join(RES_DIR, "app%d.png" % s), images[s])
	print("icons ok")


if __name__ == "__main__":
	main()
