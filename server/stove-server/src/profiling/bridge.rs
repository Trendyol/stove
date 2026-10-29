use super::{Frame, Profile, Recording, invalid};
use serde::Deserialize;
use std::ffi::{CStr, c_char, c_int, c_longlong, c_void};
unsafe extern "C" {
  fn StoveProfileConvert(
    data: *const c_void,
    size: c_int,
    labels: *const c_void,
    label_size: c_int,
    from: c_longlong,
    until: c_longlong,
    rate: c_longlong,
  ) -> *mut c_char;
  fn StoveProfileFree(output: *mut c_void);
}
#[derive(Deserialize)]
struct Output {
  #[serde(default)]
  frame_table: Vec<Frame>,
  #[serde(default)]
  profiles: Vec<Profile>,
  error: Option<String>,
}
pub(super) fn convert(
  data: &[u8],
  labels: &[u8],
  from: i64,
  until: i64,
  rate: i64,
) -> crate::error::Result<Recording> {
  // Both inputs are bounded before entry. Go copies them and returns malloc-owned UTF-8.
  let raw = unsafe {
    StoveProfileConvert(
      data.as_ptr().cast(),
      c_int::try_from(data.len()).map_err(|_| invalid("JFR input too large"))?,
      labels.as_ptr().cast(),
      c_int::try_from(labels.len()).map_err(|_| invalid("Labels input too large"))?,
      from,
      until,
      rate,
    )
  };
  if raw.is_null() {
    return Err(invalid("JFR converter returned no data"));
  }
  let result = unsafe { serde_json::from_slice::<Output>(CStr::from_ptr(raw).to_bytes()) };
  unsafe { StoveProfileFree(raw.cast()) };
  let out = result?;
  if let Some(error) = out.error {
    return Err(invalid(format!("JFR conversion failed: {error}")));
  }
  Ok(Recording {
    frame_table: out.frame_table,
    profiles: out.profiles,
    sample_rate: rate,
  })
}
