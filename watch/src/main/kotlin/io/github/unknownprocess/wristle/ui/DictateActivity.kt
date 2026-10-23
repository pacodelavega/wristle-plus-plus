package io.github.unknownprocess.wristle.ui

// @keep(pacodelavega): One-tap quick dictation launcher for Wear OS complication shortcut

class DictateActivity : MainActivity() {
    override fun isQuickMode(): Boolean = true
}
