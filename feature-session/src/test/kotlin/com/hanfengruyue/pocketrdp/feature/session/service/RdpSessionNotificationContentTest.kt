package com.hanfengruyue.pocketrdp.feature.session.service

import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionInfo
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistrySnapshot
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

class RdpSessionNotificationContentTest {
    @Test
    fun singleSessionShowsComputerNameAndHost() {
        val content = buildRdpSessionNotificationContent(
            snapshot = RdpSessionRegistrySnapshot(
                activeCount = 1,
                connectedCount = 1,
                sessions = listOf(session(1L, "NAS", "8.8.8.8:63389", RdpSessionRuntimeState.CONNECTED)),
            ),
            strings = strings,
        )

        assertEquals("NAS", content.title)
        assertEquals("已连接 · 8.8.8.8:63389", content.text)
        assertEquals(emptyList<String>(), content.bigLines)
        assertEquals("断开", content.disconnectAction)
    }

    @Test
    fun multipleSessionsShowSummaryAndExpandedLines() {
        val content = buildRdpSessionNotificationContent(
            snapshot = RdpSessionRegistrySnapshot(
                activeCount = 2,
                connectedCount = 1,
                connectingCount = 1,
                sessions = listOf(
                    session(1L, "NAS", "8.8.8.8:63389", RdpSessionRuntimeState.CONNECTED),
                    session(2L, "Desktop", "10.0.0.3:3389", RdpSessionRuntimeState.CONNECTING),
                ),
            ),
            strings = strings,
        )

        assertEquals("正在控制 2 台电脑", content.title)
        assertEquals("已连接 1 · 连接中 1 · 重连 0", content.text)
        assertEquals(
            listOf("NAS · 已连接 · 8.8.8.8:63389", "Desktop · 正在连接… · 10.0.0.3:3389"),
            content.bigLines,
        )
        assertEquals("断开全部", content.disconnectAction)
    }

    @Test
    fun multipleSessionsCapsExpandedLines() {
        val content = buildRdpSessionNotificationContent(
            snapshot = RdpSessionRegistrySnapshot(
                activeCount = 6,
                connectedCount = 6,
                sessions = (1L..6L).map { id ->
                    session(id, "PC$id", "10.0.0.$id:3389", RdpSessionRuntimeState.CONNECTED)
                },
            ),
            strings = strings,
        )

        assertEquals(6, content.bigLines.size)
        assertEquals("另有 1 台…", content.bigLines.last())
    }

    private fun session(
        id: Long,
        name: String,
        host: String,
        state: RdpSessionRuntimeState,
    ): RdpSessionInfo =
        RdpSessionInfo(connectionId = id, displayName = name, hostLabel = host, state = state)

    private companion object {
        val strings = RdpSessionNotificationStrings(
            defaultTitle = "远程桌面会话",
            connecting = "正在连接…",
            connected = "已连接",
            reconnecting = "连接中断，正在重连…",
            failed = "连接失败",
            disconnect = "断开",
            disconnectAll = "断开全部",
            multiTitleFormat = "正在控制 %1\$d 台电脑",
            multiSummaryFormat = "已连接 %1\$d · 连接中 %2\$d · 重连 %3\$d",
            moreSessionsFormat = "另有 %1\$d 台…",
        )
    }
}
