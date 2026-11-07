package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

/**
 * 手机输入会话的存活策略。
 *
 * 这一组断言守的是 2026-11-07 那个真 bug：电视一开始播放就收起弹窗，
 * 顺手把会话也关了，用户手机里那个二维码当场作废（扫完码还没输剧名，端口就没了）。
 */
class PhoneSessionPolicyTest {
    private class FakeSession: java.io.Closeable {
        var closed=false
        override fun close() { closed=true }
    }

    @Test fun `a session survives while the app keeps running`() {
        val policy=PhoneSessionPolicy()
        val session=FakeSession()
        policy.attach(session,"http://192.168.1.25:1234/abc")
        assertTrue(policy.hasSession)
        assertEquals("http://192.168.1.25:1234/abc",policy.url)
        // 收起弹窗（播放、换页）不该碰会话
        assertFalse(session.closed)
        assertTrue(policy.hasSession)
    }

    @Test fun `a new session leaves the previous one to the server grace period`() {
        // 换新码时**不能**急着把旧会话 close()：
        // 服务端本来就有宽限期（GRACE_MILLIS），过期后端口还留着，
        // 手机扫到旧码会看到一句中文提示，而不是对着死地址转圈到超时。
        // 急着关掉，等于把用户手里刚扫的码当场作废。
        val policy=PhoneSessionPolicy()
        val first=FakeSession()
        val second=FakeSession()
        policy.attach(first,"http://a/1")
        policy.attach(second,"http://b/2")
        assertFalse(first.closed)
        assertFalse(second.closed)
        assertEquals("http://b/2",policy.url)
        assertTrue(policy.hasSession)
    }

    @Test fun `a replaced session expiring does not tear down the current one`() {
        // 2026-11-07 晚上那次的真凶：屏幕上换过一张码之后，上一张码的 30 分钟定时器到点，
        // 无条件回调「换码」→ 关掉的是**当前**这张（刚生成的新码），再换第三张。
        // 用户手里刚扫的那张因此变成死地址，Safari 报「无法访问此网站」，
        // 而 PC 用同样地址 curl 得到 200 —— 看起来像手机网络问题，其实是码自己被换掉了。
        val policy=PhoneSessionPolicy()
        val first=FakeSession()
        val second=FakeSession()
        policy.attach(first,"http://a/1")
        policy.attach(second,"http://b/2")
        var replaced=false
        policy.expire(first) { replaced=true }   // 旧码的定时器响了
        assertFalse("旧码过期不许把当前这张新码关掉",replaced)
        assertTrue("当前码还在手上",policy.isCurrent(second))
        policy.expire(second) { replaced=true }  // 当前码真的过期了
        assertTrue("当前码过期要换码",replaced)
    }

    @Test fun `releasing the session closes it and clears the url`() {
        val policy=PhoneSessionPolicy()
        val session=FakeSession()
        policy.attach(session,"http://a/1")
        policy.release()
        assertTrue(session.closed)
        assertFalse(policy.hasSession)
        assertNull(policy.url)
    }
}