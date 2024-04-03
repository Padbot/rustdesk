use crate::android::ffi::*;
use crate::{Frame, Pixfmt};
use crate::ARGBRotate; //派宝改动：使用linyuv库旋转画面数据
use lazy_static::lazy_static;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Mutex;
use std::{io, time::Duration};
use crate::RotationMode::*;
use crate::RotationMode;

lazy_static! {
    static ref SCREEN_SIZE: Mutex<(u16, u16, u16)> = Mutex::new((0, 0, 0)); // (width, height, scale)
}

pub struct Capturer {
    display: Display,
    rgba: Vec<u8>,
    //派宝改动：旋转数据辅助变量
    tmp_bgra: Vec<u8>,
    rotation: RotationMode,
    //改动结束
    saved_raw_data: Vec<u8>, // for faster compare and copy
}

impl Capturer {
    pub fn new(display: Display) -> io::Result<Capturer> {
        Ok(Capturer {
            display,
            rgba: Vec::new(),
            //派宝改动：旋转数据辅助变量
            tmp_bgra: Vec::new(),
            rotation: kRotate0,
            //改动结束
            saved_raw_data: Vec::new(),
        })
    }

    pub fn width(&self) -> usize {
        self.display.width() as usize
    }

    pub fn height(&self) -> usize {
        self.display.height() as usize
    }
}

impl crate::TraitCapturer for Capturer {
    fn frame<'a>(&'a mut self, _timeout: Duration) -> io::Result<Frame<'a>> {
        if let Some(buf) = get_video_raw() {
            crate::would_block_if_equal(&mut self.saved_raw_data, buf)?;
            // Is it safe to directly return buf without copy?
            self.rgba.resize(buf.len(), 0);
            //派宝改动：旋转数据具体操作
            self.tmp_bgra.resize(buf.len(), 0);
            //如果self.rotation不为负数，需重新获取
            if self.rotation == kRotate0 {
                if let Some(temp) = get_rotation() {
                    self.rotation = match temp {
                        0 => kRotate270,
                        90 => kRotate90,
                        180 => kRotate180,
                        270 => kRotate270,
                        _ => kRotate0
                    };
                }
            }
            unsafe {
                if self.rotation != kRotate0 {
                    ARGBRotate(
                        buf.as_ptr(),
                        if self.rotation == kRotate180 {
                            4 * self.width() as i32
                        } else {
                            4 * self.height() as i32
                        },
                        self.rgba.as_mut_ptr(),
                        4 * self.width() as i32,
                        if self.rotation == kRotate180 {
                            self.width() as i32
                        } else {
                            self.height() as i32
                        },
                        if self.rotation == kRotate180 {
                            self.height() as i32
                        } else {
                            self.width() as i32
                        },
                        self.rotation,
                    );
                    //rgba_to_i420(self.width(), self.height(), &self.tmp_bgra, &mut self.rgba);
                    //std::ptr::copy_nonoverlapping(&self.tmp_bgra.as_ptr(), self.rgba.as_mut_ptr(), buf.len())
                } else {
                    std::ptr::copy_nonoverlapping(buf.as_ptr(), self.rgba.as_mut_ptr(), buf.len())
                }
            }
            //改动结束
            Ok(Frame::PixelBuffer(PixelBuffer::new(
                &self.rgba,
                if self.rotation == kRotate180 || self.rotation == kRotate0 {
                    self.width()
                } else {
                    self.height()
                },
                if self.rotation == kRotate180 || self.rotation == kRotate0 {
                    self.height()
                } else {
                    self.width()
                },
            )))
        } else {
            return Err(io::ErrorKind::WouldBlock.into());
        }
    }
}


pub struct PixelBuffer<'a> {
    data: &'a [u8],
    width: usize,
    height: usize,
    stride: Vec<usize>,
}

impl<'a> PixelBuffer<'a> {
    pub fn new(data: &'a [u8], width: usize, height: usize) -> Self {
        let stride0 = data.len() / height;
        let mut stride = Vec::new();
        stride.push(stride0);
        PixelBuffer {
            data,
            width,
            height,
            stride,
        }
    }
}

impl<'a> crate::TraitPixelBuffer for PixelBuffer<'a> {
    fn data(&self) -> &[u8] {
        self.data
    }

    fn width(&self) -> usize {
        self.width
    }

    fn height(&self) -> usize {
        self.height
    }

    fn stride(&self) -> Vec<usize> {
        self.stride.clone()
    }

    fn pixfmt(&self) -> Pixfmt {
        Pixfmt::RGBA
    }
}

pub struct Display {
    default: bool,
    rect: Rect,
}

#[derive(Copy, Clone, Debug, Hash, Eq, PartialEq)]
struct Rect {
    pub x: i16,
    pub y: i16,
    pub w: u16,
    pub h: u16,
}

impl Display {
    pub fn primary() -> io::Result<Display> {
        let mut size = SCREEN_SIZE.lock().unwrap();
        if size.0 == 0 || size.1 == 0 {
            *size = get_size().unwrap_or_default();
        }
        Ok(Display {
            default: true,
            rect: Rect {
                x: 0,
                y: 0,
                w: size.0,
                h: size.1,
            },
        })
    }

    pub fn all() -> io::Result<Vec<Display>> {
        Ok(vec![Display::primary()?])
    }

    pub fn width(&self) -> usize {
        self.rect.w as usize
    }

    pub fn height(&self) -> usize {
        self.rect.h as usize
    }

    pub fn origin(&self) -> (i32, i32) {
        let r = self.rect;
        (r.x as _, r.y as _)
    }

    pub fn is_online(&self) -> bool {
        true
    }

    pub fn is_primary(&self) -> bool {
        self.default
    }

    pub fn name(&self) -> String {
        "Android".into()
    }

    pub fn refresh_size() {
        let mut size = SCREEN_SIZE.lock().unwrap();
        *size = get_size().unwrap_or_default();
    }

    // Big android screen size will be shrinked, to improve performance when screen-capturing and encoding
    // e.g 2280x1080 size will be set to 1140x540, and `scale` is 2
    // need to multiply by `4` (2*2) when compute the bitrate
    pub fn fix_quality() -> u16 {
        let scale = SCREEN_SIZE.lock().unwrap().2;
        if scale <= 0 {
            1
        } else {
            scale * scale
        }
    }
}

fn get_size() -> Option<(u16, u16, u16)> {
    let res = call_main_service_get_by_name("screen_size").ok()?;
    if let Ok(json) = serde_json::from_str::<HashMap<String, Value>>(&res) {
        if let (Some(Value::Number(w)), Some(Value::Number(h)), Some(Value::Number(scale))) =
            (json.get("width"), json.get("height"), json.get("scale"))
        {
            let w = w.as_i64()? as _;
            let h = h.as_i64()? as _;
            let scale = scale.as_i64()? as _;
            return Some((w, h, scale));
        }
    }
    None
}

//派宝改动：与Android端交互获取旋转角度
fn get_rotation() -> Option<u16> {
    let res = call_main_service_get_by_name("rotation").ok()?;
    if let Ok(json) = serde_json::from_str::<HashMap<String, Value>>(&res) {
        if let Some(Value::Number(rotation)) = json.get("rotation") {
            let rotation = rotation.as_i64()? as _;
            return Some(rotation);
        }
    }
    Some(0)
}
//改动结束
