use crate::android::ffi::*;
use crate::{Frame, Pixfmt};
use lazy_static::lazy_static;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Mutex;
use std::{io, time::Duration};

lazy_static! {
   pub(crate)  static ref SCREEN_SIZE: Mutex<(u16, u16, u16)> = Mutex::new((0, 0, 0)); // (width, height, scale)
}

pub struct Capturer {
    display: Display,
    rgba: Vec<u8>,
    //派宝改动：旋转数据辅助变量
    tmp_bgra: Vec<u8>,
    rotation: u16,
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
    fn frame<'a>(&'a mut self, _timeout: Duration) -> io::Result<Frame<'a>> {
        if get_video_raw(&mut self.rgba, &mut self.saved_raw_data).is_some() {
            // 计算目标宽高（对外一致），以及源缓冲区实际宽高（可能因 90/270 互换）
            let target_w = self.width();
            let target_h = self.height();
            let rotation = get_rotation().unwrap_or(0) % 360;
            self.rotation = rotation as u16;

            let (src_w, src_h) = match rotation {
                90 | 270 => (target_h, target_w),
                _ => (target_w, target_h),
            };

            // 对于 0/360 不旋转，直接输出
            if rotation == 0 || rotation == 360 {
                return Ok(Frame::PixelBuffer(PixelBuffer::new(
                    &self.rgba,
                    target_w,
                    target_h,
                )));
            }

            // 其他角度进行旋转，输出尺寸保持 target_w x target_h
            let src_stride = src_w * 4;
            let dst_stride = target_w * 4; // 旋转后输出的行跨度以目标宽为准
            let dst_len = target_w * target_h * 4;
            self.tmp_bgra.resize(dst_len, 0);

            let mode = match rotation {
                90 => crate::RotationMode::kRotate90,
                180 => crate::RotationMode::kRotate180,
                270 => crate::RotationMode::kRotate270,
                _ => crate::RotationMode::kRotate0,
            };

            unsafe {
                crate::common::ARGBRotate(
                    self.rgba.as_ptr(),
                    src_stride as _,
                    self.tmp_bgra.as_mut_ptr(),
                    dst_stride as _,
                    src_w as _,
                    src_h as _,
                    mode,
                );
            }

            std::mem::swap(&mut self.rgba, &mut self.tmp_bgra);
            self.tmp_bgra.clear();

            Ok(Frame::PixelBuffer(PixelBuffer::new(
                &self.rgba,
                target_w,
                target_h,
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

pub fn is_start() -> Option<bool> {
    let res = call_main_service_get_by_name("is_start").ok()?;
    Some(res == "true")
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
