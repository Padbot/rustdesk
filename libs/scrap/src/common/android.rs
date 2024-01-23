use crate::android::ffi::*;
use crate::rgba_to_i420;
use crate::ARGBRotate; //派宝改动：使用linyuv库旋转画面数据
use lazy_static::lazy_static;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Mutex;
use std::{io, time::Duration};

lazy_static! {
    static ref SCREEN_SIZE: Mutex<(u16, u16, u16)> = Mutex::new((0, 0, 0)); // (width, height, scale)
}

pub struct Capturer {
    display: Display,
    bgra: Vec<u8>,
    //派宝改动：旋转数据辅助变量
    tmp_bgra: Vec<u8>,
    rotation: u16,
    //改动结束
    saved_raw_data: Vec<u8>, // for faster compare and copy
}

impl Capturer {
    pub fn new(display: Display, _yuv: bool) -> io::Result<Capturer> {
        Ok(Capturer {
            display,
            bgra: Vec::new(),
            //派宝改动：旋转数据辅助变量
            tmp_bgra: Vec::new(),
            rotation: 360,
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
    fn set_use_yuv(&mut self, _use_yuv: bool) {}

    fn frame<'a>(&'a mut self, _timeout: Duration) -> io::Result<Frame<'a>> {
        if let Some(buf) = get_video_raw() {
            crate::would_block_if_equal(&mut self.saved_raw_data, buf)?;
            //派宝改动：旋转数据具体操作
            self.tmp_bgra.resize(self.width() * self.height() * 4, 0);
            //如果self.rotation不为负数，需重新获取
            if self.rotation == 360 {
                if let Some(temp) = get_rotation() {
                    self.rotation = temp;
                }
            }
            if self.rotation == 360 {
                self.rotation = 0;
            }
            if self.rotation > 0 {
                unsafe {
                    ARGBRotate(
                        buf.as_ptr(),
                        if self.rotation % 180 == 0 {
                            4 * self.width() as i32
                        } else {
                            4 * self.height() as i32
                        },
                        self.tmp_bgra.as_mut_ptr(),
                        4 * self.width() as i32,
                        if self.rotation % 180 == 0 {
                            self.width() as i32
                        } else {
                            self.height() as i32
                        },
                        if self.rotation % 180 == 0 {
                            self.height() as i32
                        } else {
                            self.width() as i32
                        },
                        self.rotation as i32,
                    );
                }
                rgba_to_i420(self.width(), self.height(), &self.tmp_bgra, &mut self.bgra);
            } else {
                rgba_to_i420(self.width(), self.height(), buf, &mut self.bgra);
            }
            //改动结束
            Ok(Frame::RAW(&self.bgra))
        } else {
            return Err(io::ErrorKind::WouldBlock.into());
        }
    }
}

pub enum Frame<'a> {
    RAW(&'a [u8]),
    Empty,
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
