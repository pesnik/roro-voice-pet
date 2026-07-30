package dev.pesnik.roro.pet

/**
 * Mirrors the desktop app's state-priority.js ordering (error > attention >
 * working > thinking > idle) and app/themes/calico/theme.json's timings.
 * Priority numbers are kept identical to the desktop values (not renumbered
 * to be contiguous) so a future state (e.g. notification, sleeping) can be
 * added without touching existing ones.
 */
enum class PetState(val priority: Int, val minDisplayMs: Long, val autoReturnMs: Long?) {
    Idle(priority = 1, minDisplayMs = 0, autoReturnMs = null),
    Thinking(priority = 2, minDisplayMs = 1000, autoReturnMs = null),
    Working(priority = 3, minDisplayMs = 1000, autoReturnMs = null),
    Attention(priority = 5, minDisplayMs = 5000, autoReturnMs = 5000),
    Error(priority = 8, minDisplayMs = 5000, autoReturnMs = 5000),
}
