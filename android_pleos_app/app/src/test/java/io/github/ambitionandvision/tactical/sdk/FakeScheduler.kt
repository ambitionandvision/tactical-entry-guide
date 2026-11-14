package io.github.ambitionandvision.tactical.sdk

/** 가상 시계. advance() 로 예약 작업을 시간 순서대로 실행한다. */
class FakeScheduler : FixScheduler {
    private data class Task(val at: Long, val seq: Long, val run: () -> Unit)

    var now = 0L
        private set
    private var seq = 0L
    private val tasks = ArrayList<Task>()
    val delays = ArrayList<Long>()

    override fun postDelayed(delayMs: Long, task: () -> Unit) {
        delays.add(delayMs)
        tasks.add(Task(now + delayMs, seq++, task))
    }

    override fun cancelAll() {
        tasks.clear()
    }

    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { it.at <= end }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = next.at
            next.run()
        }
        now = end
    }
}
