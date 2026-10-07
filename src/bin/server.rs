#[tokio::main(worker_threads = 2)]
async fn main() -> anyhow::Result<()> {
    reexaudio::server::run().await
}
