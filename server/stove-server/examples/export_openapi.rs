fn main() -> Result<(), Box<dyn std::error::Error>> {
  println!("{}", stove::http::openapi_document().to_pretty_json()?);
  Ok(())
}
