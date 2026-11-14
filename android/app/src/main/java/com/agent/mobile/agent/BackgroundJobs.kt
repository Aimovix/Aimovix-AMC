package com.agent.mobile.agent

/**
 * Long-running work (servers, downloads, builds) as background jobs in Termux. A job runs detached
 * with nohup, writes its output to ~/.amc/jobs/<id>.log and its exit code to <id>.exit, so it keeps
 * running across reconnects and continuations and can be checked later. Works with every installed
 * bridge: it only uses ordinary shell commands. The user's command itself is stored in a script file
 * and never spliced into these commands, so no quoting can break out.
 */
object BackgroundJobs {

    const val DIR = "~/.amc/jobs"
    const val MAX_WAIT_SECONDS = 300
    const val MAX_OUTPUT_BYTES = 6_000
    private val ID = Regex("^j[0-9a-z]{4,16}$")

    fun newId(now: Long = System.currentTimeMillis()): String =
        "j" + now.toString(36) + (0 until 2).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun scriptPath(id: String) = "$DIR/$id.sh"

    /** Starts the job whose command was written to [scriptPath]. Prints the pid. */
    fun startCommand(id: String): String {
        val d = DIR
        // Plain ";" before nohup: with "&&" the whole list would go to the background and $! would
        // not be the job's pid.
        // set -m: the job gets its own process group (pgid = its pid), so job_stop can end it with
        // everything it started without touching anything else.
        return "mkdir -p $d; rm -f $d/$id.exit; set -m; " +
            "nohup bash -c 'bash $d/$id.sh; echo \$? > $d/$id.exit' > $d/$id.log 2>&1 < /dev/null & " +
            "echo \$! > $d/$id.pid; date +%s > $d/$id.start; echo \"started pid \$(cat $d/$id.pid)\""
    }

    /**
     * Status and new output of job [id] from byte [offset] on. Waits up to [waitSeconds] until the
     * job ends or prints something new, so polling does not need many steps.
     */
    fun statusCommand(id: String, offset: Long, waitSeconds: Int): String {
        val d = DIR
        val wait = waitSeconds.coerceIn(0, MAX_WAIT_SECONDS)
        val off = offset.coerceAtLeast(0)
        return "f=$d/$id; [ -f \$f.pid ] || { echo 'NO_SUCH_JOB'; exit 0; }; " +
            "i=0; while [ \$i -lt $wait ] && [ ! -f \$f.exit ] && [ \$(wc -c < \$f.log) -le $off ]; do sleep 1; i=\$((i+1)); done; " +
            "pid=\$(cat \$f.pid); size=\$(wc -c < \$f.log); now=\$(date +%s); start=\$(cat \$f.start 2>/dev/null || echo \$now); " +
            "if [ -f \$f.exit ]; then echo \"STATE exited \$(cat \$f.exit)\"; " +
            "elif kill -0 \$pid 2>/dev/null; then echo \"STATE running\"; else echo 'STATE gone'; fi; " +
            "echo \"ELAPSED \$((now-start))\"; echo \"SIZE \$size\"; echo '---'; " +
            "tail -c +${off + 1} \$f.log | head -c $MAX_OUTPUT_BYTES"
    }

    /** Stops job [id] with its whole process group (the job's own session). */
    fun stopCommand(id: String): String {
        val d = DIR
        return "f=$d/$id; [ -f \$f.pid ] || { echo 'NO_SUCH_JOB'; exit 0; }; pid=\$(cat \$f.pid); " +
            "pg=\$(awk '{print \$5}' /proc/\$pid/stat 2>/dev/null); " +
            "if [ \"\$pg\" = \"\$pid\" ]; then kill -TERM -- -\$pid 2>/dev/null; fi; kill -TERM \$pid 2>/dev/null; " +
            "sleep 2; if [ \"\$pg\" = \"\$pid\" ]; then kill -KILL -- -\$pid 2>/dev/null; fi; kill -KILL \$pid 2>/dev/null; [ -f \$f.exit ] || echo 143 > \$f.exit; echo stopped"
    }

    /** One line per job: id, state, age and the first line of its command. */
    fun listCommand(): String {
        val d = DIR
        return "mkdir -p $d; cd $d; n=0; for p in *.pid; do [ -f \"\$p\" ] || continue; id=\${p%.pid}; n=\$((n+1)); " +
            "if [ -f \$id.exit ]; then s=\"exited \$(cat \$id.exit)\"; elif kill -0 \$(cat \$p) 2>/dev/null; then s=running; else s=gone; fi; " +
            "echo \"\$id  \$s  \$(head -c 100 \$id.sh 2>/dev/null | head -n 1)\"; done; [ \$n -gt 0 ] || echo 'No background jobs.'"
    }

    data class Status(val state: String, val exitCode: Int?, val elapsedSeconds: Long, val size: Long, val output: String)

    /** Parses the output of [statusCommand]; null for an unknown job. */
    fun parseStatus(stdout: String): Status? {
        if (stdout.trim() == "NO_SUCH_JOB") return null
        val head = stdout.substringBefore("\n---\n")
        val output = stdout.substringAfter("\n---\n", "")
        val stateLine = head.lines().firstOrNull { it.startsWith("STATE ") }?.removePrefix("STATE ")?.trim() ?: return null
        val parts = stateLine.split(' ')
        return Status(
            state = parts[0],
            exitCode = parts.getOrNull(1)?.trim()?.toIntOrNull(),
            elapsedSeconds = head.lines().firstOrNull { it.startsWith("ELAPSED ") }?.removePrefix("ELAPSED ")?.trim()?.toLongOrNull() ?: 0,
            size = head.lines().firstOrNull { it.startsWith("SIZE ") }?.removePrefix("SIZE ")?.trim()?.toLongOrNull() ?: 0,
            output = output
        )
    }

    /** What the model sees for a status call. */
    fun describeStatus(id: String, offset: Long, s: Status): String = buildString {
        when (s.state) {
            "exited" -> append("Job $id finished with exit code ${s.exitCode ?: "?"} after ${s.elapsedSeconds} s.")
            "running" -> append("Job $id is still running (${s.elapsedSeconds} s).")
            else -> append("Job $id is no longer running and left no exit code (killed, or the phone restarted).")
        }
        val shownEnd = minOf(s.size, offset + s.output.toByteArray().size)
        append(" Log: ${s.size} bytes")
        if (s.output.isEmpty()) {
            append(", nothing new since offset $offset.")
        } else {
            append(", showing bytes $offset-$shownEnd.")
            if (shownEnd < s.size) append(" More output: job_status with offset=$shownEnd.")
            else if (s.state == "running") append(" Next time ask with offset=$shownEnd for new output only.")
            append("\n").append(s.output)
        }
    }
}
