package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackGestureTest {
    @Test fun 斜滑只锁一个方向不会既拖进度又调音量() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,0L)
        // 先斜着走：横向位移更大，锁横向
        m.onMove(140f,112f)
        // 之后改成纵向走，方向不能改判 —— 不能再发音量
        assertEquals(TouchIntent.None,m.onMove(150f,200f))
        // 抬起仍然是横滑的落点
        assertTrue(m.onUp(150f,200f,10L) is TouchIntent.Scrub)
    }

    @Test fun 斜滑锁横向后纵滑不产生音量档位() {
        // 单独验音量这一路：锁了横向之后再往纵向走，move 必须一直是 None（不发音量）。
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,0L)
        m.onMove(140f,112f)              // 锁横向
        assertEquals(TouchIntent.None,m.onMove(150f,200f))   // 改成纵向走
        assertEquals(TouchIntent.None,m.onMove(150f,260f))   // 再往下 60dp
        assertTrue(m.onUp(150f,260f,10L) is TouchIntent.Scrub)
    }

    @Test fun 横滑按相对位移算目标位置() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,60_000L,600_000L,0L)
        m.onMove(200f,100f)               // +100dp = +50 秒
        val up=m.onUp(200f,100f,10L) as TouchIntent.Scrub
        assertEquals(110_000L,up.targetMs)
    }

    @Test fun 松手时按最终坐标算目标位置() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,0L)
        m.onMove(200f,100f)               // 拖到 +100dp
        val up=m.onUp(250f,100f,10L) as TouchIntent.Scrub   // 松手时又走了 50dp
        assertEquals(75_000L,up.targetMs)  // 150dp * 0.5 秒 = 75 秒
    }

    @Test fun 横滑越界夹在零和总时长之间() {
        val forward=TouchGestureMachine().apply {
            onDown(100f,100f,0L,600_000L,0L); onMove(1000f,100f)
        }
        assertEquals(450_000L,(forward.onUp(1000f,100f,10L) as TouchIntent.Scrub).targetMs)
        val back=TouchGestureMachine().apply {
            onDown(100f,100f,10_000L,600_000L,0L); onMove(0f,100f)
        }
        assertEquals(0L,(back.onUp(0f,100f,10L) as TouchIntent.Scrub).targetMs)
    }

    @Test fun 拖动期间不给意图抬起才给一次() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,0L)
        assertEquals(TouchIntent.None,m.onMove(150f,100f))
        assertEquals(TouchIntent.None,m.onMove(200f,100f))
        assertTrue(m.onUp(200f,100f,10L) is TouchIntent.Scrub)
    }

    @Test fun 总时长未知时不产生拖动意图() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,30_000L,0L,0L)
        m.onMove(200f,100f)
        assertEquals(TouchIntent.None,m.onUp(200f,100f,10L))
    }

    @Test fun 单击要等双击超时才确认() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,1_000L)
        assertEquals(TouchIntent.None,m.onUp(100f,100f,1_000L))
        assertEquals(TouchIntent.None,m.onTimeAdvance(1_100L))
        assertEquals(TouchIntent.ToggleHud,m.onTimeAdvance(1_300L))
    }

    @Test fun 单击过期后按下时补发那次单击的意图() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,1_000L)
        m.onUp(100f,100f,1_000L)            // 挂起一次单击
        // 500ms 后才按下，中间没驱动过 onTimeAdvance
        assertEquals(TouchIntent.ToggleHud,m.onDown(100f,100f,0L,600_000L,1_500L))
        // 补发之后，这一次按下是全新的单击，照旧挂起
        assertEquals(TouchIntent.None,m.onUp(100f,100f,1_510L))
        assertEquals(TouchIntent.ToggleHud,m.onTimeAdvance(1_900L))
    }

    @Test fun 双击压掉单击只给一次播放暂停() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,1_000L)
        m.onUp(100f,100f,1_000L)
        // 第二下：离上次抬起 100ms，不到 300ms
        m.onDown(100f,100f,0L,600_000L,1_100L)
        assertEquals(TouchIntent.PlayPause,m.onUp(100f,100f,1_100L))
        // 超时之后也不该再补一个单击
        assertEquals(TouchIntent.None,m.onTimeAdvance(2_000L))
    }

    @Test fun 漏掉一次抬起后单击不会被误判成双击() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,1_000L)
        m.onUp(100f,100f,1_000L)                 // 第一次单击
        m.onDown(100f,100f,0L,600_000L,1_100L)   // 100ms 后按下 → 判成双击的第二下
        // 故意不调用 onUp：模拟 app 层把 ACTION_CANCEL 丢了
        assertEquals(TouchIntent.None,m.onDown(100f,100f,0L,600_000L,2_000L))  // 再来一次全新按下
        assertEquals(TouchIntent.None,m.onUp(100f,100f,2_010L))                 // 必须是普通单击，不能是 PlayPause
        assertEquals(TouchIntent.ToggleHud,m.onTimeAdvance(2_400L))             // 且它的 HUD 切换没丢
    }

    @Test fun 复位后双击标志不残留() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,1_000L)
        m.onUp(100f,100f,1_000L)
        m.onDown(100f,100f,0L,600_000L,1_100L)   // 双击的第二下
        m.reset()
        assertEquals(TouchIntent.None,m.onDown(100f,100f,0L,600_000L,2_000L))
        assertEquals(TouchIntent.None,m.onUp(100f,100f,2_010L))
    }

    @Test fun 纵滑按档位发增量音量() {
        val m=TouchGestureMachine()
        m.onDown(100f,200f,0L,600_000L,0L)
        assertEquals(TouchIntent.Volume(1),m.onMove(100f,176f))   // 上滑 24dp = 一档
        assertEquals(TouchIntent.Volume(1),m.onMove(100f,152f))   // 再一档
        assertEquals(TouchIntent.Volume(-1),m.onMove(100f,160f))  // 退回 40dp = 退一档
    }

    @Test fun 音量严格每二十四dp一档() {
        val m=TouchGestureMachine()
        m.onDown(100f,200f,0L,600_000L,0L)
        assertEquals(TouchIntent.None,m.onMove(100f,188f))          // 上滑 12dp 不到一档
        assertEquals(TouchIntent.Volume(1),m.onMove(100f,176f))    // 24dp 一档
        assertEquals(TouchIntent.None,m.onMove(100f,164f))          // 36dp 还是一档
        assertEquals(TouchIntent.Volume(1),m.onMove(100f,152f))    // 48dp 两档
    }
}
