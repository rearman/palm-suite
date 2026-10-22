//! The offscreen capture of `--shot`, for the screenshots of each phase of the port.

use fltk::enums::ColorDepth;
use fltk::prelude::*;
use fltk::surface::ImageSurface;
use fltk::window::Window;

/// Draws `win` offscreen at the display scale and writes it to `path` as a binary PPM.
pub fn save(win: &Window, path: &str) -> std::io::Result<()> {
	let surf = ImageSurface::new(win.w(), win.h(), true);
	ImageSurface::push_current(&surf);
	surf.draw(win, 0, 0);
	ImageSurface::pop_current();
	let img = surf.image().expect("surface image");
	let (w, h) = (img.data_w() as usize, img.data_h() as usize);
	let channels = match img.depth() {
		ColorDepth::L8 => 1,
		ColorDepth::La8 => 2,
		ColorDepth::Rgb8 => 3,
		ColorDepth::Rgba8 => 4,
	};
	let mut out = format!("P6\n{w} {h}\n255\n").into_bytes();
	for px in img.to_rgb_data().chunks(channels) {
		match channels {
			1 | 2 => out.extend_from_slice(&[px[0], px[0], px[0]]),
			_ => out.extend_from_slice(&px[..3]),
		}
	}
	std::fs::write(path, out)
}
