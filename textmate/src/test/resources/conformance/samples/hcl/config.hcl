# A job for a scheduler.
job "leaf-update-check" {
  datacenters = ["dc1"]
  type        = "batch"

  periodic {
    cron             = "*/5 * * * *"
    prohibit_overlap = true
  }

  group "check" {
    count = 1
    task "fetch" {
      driver = "exec"
      config {
        command = "/usr/bin/curl"
        args    = ["-fsS", "${NOMAD_META_url}/latest.json"]
      }
      /* resources */
      resources { cpu = 100 memory = 64 }
    }
  }
}
