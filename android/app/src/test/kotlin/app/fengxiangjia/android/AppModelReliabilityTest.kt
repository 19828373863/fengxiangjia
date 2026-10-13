package app.fengxiangjia.android

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.domain.Target
import app.fengxiangjia.core.editor.RoomTemplate
import app.fengxiangjia.core.library.SampleData
import app.fengxiangjia.core.persistence.DocumentCodec
import app.fengxiangjia.core.persistence.HomeLoadState
import app.fengxiangjia.core.persistence.HomeRepository
import app.fengxiangjia.core.weather.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

/** Regressions for actual editing, persistence and restoration failures discovered in the K30 audit. */
class AppModelReliabilityTest {
    private val t0 = Instant.parse("2026-10-12T16:00:00Z")
    private fun directory() = Files.createTempDirectory("fxj-reliability").toFile()
    private fun model(dir: File = directory(), full: Boolean = true) = AppModel(HomeRepository(dir), fullExperience = full) { t0 }
    private fun session(dir: File) = File(dir, "home.json.session.json")

    @Test fun failedMultiStepEditKeepsDocumentEditorAndUndoHistory() {
        val m = model()
        val id = m.room!!.fans.first().id
        assertTrue(m.edit { it.moveFan(id, Vec2(3.0, 1.0)) })
        val before = m.document
        val editor = m.editor()!!
        assertFalse(m.edit { it.setFanPower(id, 0.9); it.placeFan(Vec2(99.0, 1.0)) })
        assertEquals(before, m.document)
        assertEquals(before.home.rooms.first(), editor.room)
        assertSame(editor, m.editor())
        m.undo()
        assertEquals(Vec2(4.3, 0.8), m.room!!.fans.first().position)
        assertEquals(0.6, m.room!!.fans.first().power)
        assertFalse(m.editor()!!.canUndo)
    }

    @Test fun failureAfterUndoKeepsRedoBranch() {
        val m = model()
        val id = m.room!!.fans.first().id
        m.edit { it.setFanPower(id, 0.9) }; m.undo()
        val before = m.document
        assertFalse(m.edit { it.setFanYaw(id, 90.0); it.placeFan(Vec2(-10.0, 1.0)) })
        assertEquals(before, m.document)
        assertTrue(m.editor()!!.canRedo)
        m.redo(); assertEquals(0.9, m.room!!.fans.first().power)
        assertEquals(135.0, m.room!!.fans.first().yawDegrees)
    }

    @Test fun successfulMultiStepEditIsOneUndoAction() {
        val m = model()
        val before = m.room!!
        val id = before.fans.first().id
        assertTrue(m.edit { it.setFanYaw(id, 90.0); it.setFanPower(id, 0.9) })
        m.undo(); assertEquals(before, m.room)
        assertFalse(m.editor()!!.canUndo)
        m.redo(); assertEquals(90.0, m.room!!.fans.first().yawDegrees)
        assertEquals(0.9, m.room!!.fans.first().power)
    }

    @Test fun invalidReplaceOpeningWidthDoesNotMutateEditor() {
        val m = model()
        val before = m.document
        assertFalse(m.edit { e -> e.replace(e.room.copy(openings = e.room.openings.map { it.copy(width = 100.0) })) })
        assertEquals(before, m.document)
        assertEquals(before.home.rooms.first(), m.editor()!!.room)
        assertFalse(m.editor()!!.canUndo)
    }

    @Test fun replacementCannotHideOutsideTargetsOrNonFiniteInputs() {
        val m = model()
        val before = m.document
        assertFalse(m.edit { e -> e.replace(e.room.copy(targets = e.room.targets.map { it.copy(shape = TargetShape.Circle(Vec2(20.0, 20.0), 1.0)) })) })
        assertEquals(before, m.document)
        assertFalse(m.edit { e -> e.replace(e.room.copy(fans = e.room.fans.map { it.copy(power = Double.NaN) })) })
        assertEquals(before, m.document)
    }

    @Test fun shapeShrinkCannotLeaveFurnitureOutside() {
        val m = model()
        val before = m.document
        assertFalse(m.edit { e -> e.replace(e.room.copy(polygon = RoomTemplate.Rectangle(1.0, 1.0).polygon)) })
        assertEquals(before, m.document)
    }

    @Test fun renamedRoomKeepsEditorIdentityAndUndoHistory() {
        val m = model()
        val id = m.room!!.fans.first().id
        m.edit { it.setFanPower(id, 0.8) }
        val editor = m.editor()
        m.renameRoom(m.selectedRoomId, " 主卧 ")
        assertSame(editor, m.editor())
        assertEquals("主卧", m.editor()!!.room.name)
        assertTrue(m.edit { it.setFanYaw(id, 90.0) })
        assertEquals("主卧", m.room!!.name)
        m.undo(); assertEquals("主卧", m.room!!.name)
        assertEquals(135.0, m.room!!.fans.first().yawDegrees)
        m.undo(); assertEquals("主卧", m.room!!.name)
        assertEquals(0.6, m.room!!.fans.first().power)
    }

    @Test fun observerUnsubscribeIsIdempotentAndSafeDuringNotification() {
        val m = model()
        var first = 0; var second = 0
        lateinit var unsubscribe: () -> Unit
        unsubscribe = m.observe { first++; unsubscribe() }
        val stopSecond = m.observe { second++ }
        m.reportMessage("one"); m.reportMessage("two")
        assertEquals(1, first); assertEquals(2, second)
        unsubscribe(); unsubscribe(); stopSecond(); stopSecond()
        m.reportMessage("three")
        assertEquals(1, first); assertEquals(2, second)
    }

    @Test fun failedObserverCannotTurnCommittedEditIntoFailedTransaction() {
        val m = model()
        var notifications = 0
        m.observe { throw IllegalStateException("detached observer") }
        m.observe { notifications++ }
        val id = m.room!!.fans.first().id
        assertTrue(m.edit { it.setFanPower(id, 0.9) })
        assertEquals(0.9, m.room!!.fans.first().power)
        assertEquals(1, notifications)
        assertNull(m.lastError)
    }

    @Test fun freeUserCanReplaceExampleWithOwnDimensionedRoom() {
        val m = model(full = false)
        val id = m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "客厅", 6.0, 4.0)
        assertEquals(SampleData.ROOM_ID, id)
        assertEquals(1, m.home.rooms.size)
        assertEquals("客厅", m.room!!.name)
        assertEquals(RoomTemplate.Rectangle(6.0, 4.0).polygon, m.room!!.polygon)
        assertTrue(m.room!!.fans.isEmpty()); assertTrue(m.room!!.openings.isEmpty())
        assertNull(m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "卧室", 4.0, 3.0, replaceCurrent = false))
        assertEquals(1, m.home.rooms.size)
    }

    @Test fun invalidCreateDimensionsPreserveHome() {
        val m = model()
        val before = m.document
        for ((width, depth) in listOf(Double.NaN to 3.0, 4.0 to Double.POSITIVE_INFINITY, 0.5 to 3.0, 4.0 to 31.0)) {
            assertNull(m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "bad", width, depth))
            assertEquals(before, m.document)
        }
    }

    @Test fun customLShapeUsesRequestedOuterDimensions() {
        val m = model()
        assertNotNull(m.createRoom(RoomTemplate.DEFAULT_L_SHAPE, "L 型客厅", 6.0, 4.0))
        val polygon = m.room!!.polygon
        assertEquals(6, polygon.size)
        assertEquals(6.0, polygon.maxOf { it.x }); assertEquals(4.0, polygon.maxOf { it.y })
        assertEquals(3.6, polygon[3].x, 1e-9); assertEquals(2.5, polygon[3].y, 1e-9)
    }

    @Test fun replacementClearsOnlySelectedRoomsPlansAndFeedback() {
        val m = model()
        val firstId = m.selectedRoomId
        val firstPlan = m.saveScenario("旧客厅")!!
        m.addFeedback(ComfortRating.justRight, "客厅")
        val secondId = m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "卧室", 4.0, 3.0, replaceCurrent = false)!!
        val secondRoom = m.room!!
        val secondPlan = m.saveScenario("卧室 A")!!
        m.addFeedback(ComfortRating.tooWeak, "卧室")
        m.selectRoom(firstId)
        assertEquals(firstId, m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "我的客厅", 4.0, 3.0))
        assertEquals(secondRoom, m.home.rooms.first { it.id == secondId })
        assertFalse(m.document.scenarios.any { it.id == firstPlan.id })
        assertEquals(listOf(secondPlan.id), m.document.scenarios.map { it.id })
        assertEquals(listOf(secondId), m.document.feedback.map { it.roomId })
        assertTrue(m.applyScenario(secondPlan.id))
        assertEquals(secondId, m.selectedRoomId)
    }

    @Test fun replacementThatOverlapsOtherRoomIsRejected() {
        val m = model()
        val firstId = m.selectedRoomId
        m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "卧室", 4.0, 3.0, replaceCurrent = false)
        m.selectRoom(firstId)
        val before = m.document
        assertNull(m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "重叠房间", 5.0, 8.0))
        assertEquals(before, m.document)
    }

    @Test fun deleteNonSelectedRoomPreservesSelectionAndRefreshesCachedEditorTopology() {
        val m = model()
        val firstId = m.selectedRoomId
        val editor = m.editor()!!
        val windowId = m.room!!.openings.first().id
        val g = m.document.geometryRevision
        assertTrue(m.addAdjacentRoom(0, 3.0, "阳台"))
        val adjacentId = m.selectedRoomId
        m.selectRoom(firstId)
        assertSame(editor, m.editor())
        assertTrue(m.editor()!!.room.openings.first { it.id == windowId }.connects.contains(ConnectionTarget.RoomRef(adjacentId)))
        m.deleteRoom(adjacentId)
        assertEquals(firstId, m.selectedRoomId)
        assertSame(editor, m.editor())
        assertTrue(editor.room.openings.first { it.id == windowId }.connects.contains(ConnectionTarget.Outdoor))
        assertEquals(g + 2, m.document.geometryRevision)
    }

    @Test fun unrelatedRoomGeometryChangesKeepValidPlansApplicable() {
        val m = model()
        val firstId = m.selectedRoomId
        val scenario = m.saveScenario("客厅")!!
        val secondId = m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "卧室", 4.0, 3.0, replaceCurrent = false)!!
        assertTrue(m.canApplyScenario(m.document.scenarios.first { it.id == scenario.id }))
        m.edit { it.moveVertex(2, it.room.polygon[2] + Vec2(1.0, 0.0)) }
        assertTrue(m.applyScenario(scenario.id)); assertEquals(firstId, m.selectedRoomId)
        m.deleteRoom(secondId)
        assertTrue(m.applyScenario(scenario.id))
    }

    @Test fun structuralOpeningChangeInvalidatesSavedPlanWhileFractionChangeDoesNot() {
        val m = model()
        val scenario = m.saveScenario("A")!!
        val g = m.document.geometryRevision
        val openingId = m.room!!.openings.first().id
        m.edit { it.setOpeningFraction(openingId, 0.2) }
        assertEquals(g, m.document.geometryRevision)
        assertTrue(m.canApplyScenario(scenario))
        m.edit { e -> e.replace(e.room.copy(openings = e.room.openings.map { if (it.id == openingId) it.copy(kind = OpeningKind.door) else it })) }
        assertEquals(g + 1, m.document.geometryRevision)
        val before = m.document
        assertFalse(m.applyScenario(scenario.id))
        assertEquals(before, m.document)
        assertTrue(m.lastError!!.contains("户型、门窗或家具"))
    }

    @Test fun stalePlanDoesNotBecomeCompatibleAfterUnrelatedRoomAdded() {
        val m = model()
        val scenario = m.saveScenario("旧布局")!!
        assertTrue(m.edit { it.moveVertex(2, Vec2(5.5, 4.0)) })
        m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "新房间", 4.0, 3.0, replaceCurrent = false)
        assertFalse(m.canApplyScenario(m.document.scenarios.first { it.id == scenario.id }))
        assertFalse(m.applyScenario(scenario.id))
    }

    @Test fun redoCannotOverlapSubsequentlyAddedRoomAndKeepsRedoHistory() {
        val m = model()
        m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "客厅", 4.0, 3.0)
        val firstId = m.selectedRoomId
        assertTrue(m.edit { e -> e.replace(e.room.copy(polygon = RoomTemplate.Rectangle(5.0, 3.0).polygon)) })
        m.undo()
        assertTrue(m.editor()!!.canRedo)
        assertTrue(m.addAdjacentRoom(1, 3.0, "卧室"))
        m.selectRoom(firstId)
        val before = m.document
        m.redo()
        assertEquals(before, m.document)
        assertEquals(before.home.rooms.first { it.id == firstId }, m.editor()!!.room)
        assertTrue(m.editor()!!.canRedo)
        assertTrue(m.lastError!!.contains("无法恢复这一步"))
    }

    @Test fun applyingCandidateWithStaleHashDoesNotRevertNewerEdit() {
        val m = model()
        val candidates = m.recommend()
        val candidate = candidates.first { !it.isCurrent }
        val hash = m.currentHash()!!
        m.setEnvironment(90.0, 4.0)
        val before = m.document
        assertFalse(m.applyCandidate(candidate, hash))
        assertEquals(before, m.document)
        assertTrue(m.lastError!!.contains("重新生成"))
    }

    @Test fun environmentAndSelectedRoomSurviveRestartWithIdenticalHash() {
        val dir = directory(); val m = model(dir)
        val selected = m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "卧室", 4.0, 3.0, replaceCurrent = false)!!
        m.setEnvironment(270.0, 4.5)
        val hash = m.currentHash()
        val restored = model(dir)
        assertEquals(selected, restored.selectedRoomId)
        assertEquals(m.environment, restored.environment)
        assertEquals(hash, restored.currentHash())
    }

    @Test fun firstInteractionEnvironmentAndExplicitNoWindArePersisted() {
        val dir = directory(); val m = model(dir)
        m.setEnvironment(90.0, 3.0)
        assertEquals(m.environment, model(dir).environment)
        m.setEnvironment(null, null)
        assertNull(model(dir).environment)
    }

    @Test fun forecastSourceAndAgeSurviveRestart() {
        val dir = directory(); val m = model(dir)
        val environment = Environment(EnvironmentSource.assumed, t0, 90.0, windSpeed = 3.0)
        val forecast = WeatherForecast(Location(1.35, 103.82), t0,
            listOf(HourlyWeather(t0, 90.0, 3.0)), EnvironmentSource.assumed, true, WeatherAttribution("演示数据"))
        m.applyWeather(WeatherOutcome(environment, forecast, WeatherFreshness.fresh, false, null))
        val restored = AppModel(HomeRepository(dir)) { t0.plusSeconds(7200) }
        assertEquals(environment, restored.environment)
        assertEquals(forecast, restored.weather!!.forecast)
        assertEquals(WeatherFreshness.stale, restored.weather!!.freshness)
        assertTrue(restored.weather!!.fromCache)
        assertTrue(restored.weather!!.forecast!!.isDemo)
    }

    @Test fun corruptSessionDoesNotDiscardHomeOrPlans() {
        val dir = directory(); val m = model(dir)
        m.renameRoom(m.selectedRoomId, "我的客厅")
        m.saveScenario("A")
        session(dir).writeText("broken json")
        val restored = model(dir)
        assertEquals("我的客厅", restored.room!!.name)
        assertEquals(listOf("A"), restored.document.scenarios.map { it.name })
        assertTrue(restored.loadMessage!!.contains("风况"))
    }

    @Test fun saveFailureKeepsErrorVisibleAfterSuccessfulInMemoryEdit() {
        val dir = directory(); val m = model(dir)
        m.setEnvironment(90.0, 3.0)
        val previous = File(dir, "home.json").readText()
        File(dir, "home.json.tmp").mkdir()
        val id = m.room!!.fans.first().id
        assertTrue(m.edit { it.setFanPower(id, 0.9) })
        assertEquals(0.9, m.room!!.fans.first().power)
        assertTrue(m.lastError!!.contains("保存失败"))
        assertFalse(m.lastSaveSucceeded)
        assertEquals(previous, File(dir, "home.json").readText())
    }

    @Test fun failedScenarioSaveReturnsNullAndDoesNotAddPhantomPlan() {
        val dir = directory(); val m = model(dir)
        m.setEnvironment(90.0, 3.0)
        val before = m.document
        val blocked = File(dir, "home.json.tmp"); assertTrue(blocked.mkdir())
        assertNull(m.saveScenario("保留此名称"))
        assertEquals(before, m.document)
        assertTrue(m.lastError!!.contains("方案未保存"))
        assertFalse(m.lastSaveSucceeded)
        assertTrue(blocked.delete())
        val saved = m.saveScenario("保留此名称")!!
        assertTrue(m.lastSaveSucceeded)
        val disk = DocumentCodec.decode(File(dir, "home.json").readText())
        assertEquals(listOf(saved), disk.scenarios)
    }

    @Test fun failedAtomicWriteRetainsLastValidDocument() {
        val dir = directory(); val repo = HomeRepository(dir)
        val original = SampleData.sampleDocument(); repo.save(original)
        File(dir, "home.json.tmp").mkdir()
        assertFails { repo.save(original.copy(geometryRevision = 99)) }
        assertEquals(HomeLoadState.Loaded(original), repo.load())
    }

    @Test fun deleteAllDataAlsoRemovesEnvironmentSession() {
        val dir = directory(); val m = model(dir)
        m.setEnvironment(90.0, 5.0); m.saveScenario("A")
        assertTrue(session(dir).isFile)
        m.deleteAllData()
        assertFalse(File(dir, "home.json").exists()); assertFalse(session(dir).exists())
        val restored = model(dir)
        assertEquals("示例客厅", restored.room!!.name)
        assertEquals(180.0, restored.environment!!.windFromDegrees)
        assertTrue(restored.document.scenarios.isEmpty())
    }

    @Test fun invalidEnvironmentLocationAndOrientationDoNotPoisonState() {
        val m = model()
        val environment = m.environment; val before = m.document
        m.setEnvironment(Double.NaN, 2.0); assertEquals(environment, m.environment)
        m.setEnvironment(90.0, -1.0); assertEquals(environment, m.environment)
        m.setOrientation(Double.POSITIVE_INFINITY); assertEquals(before, m.document)
        m.setLocation(Double.NaN, 100.0); assertEquals(before, m.document)
        m.setLocation(91.0, 100.0); assertEquals(before, m.document)
        m.setEnvironment(-90.0, 0.0); assertEquals(270.0, m.environment!!.windFromDegrees)
        m.setOrientation(450.0); assertEquals(90.0, m.home.orientationDegrees)
    }

    @Test fun retrySavePersistsPendingRoomEditAndLatestSession() {
        val dir = directory(); val m = model(dir)
        m.setEnvironment(90.0, 3.0)
        val blocked = File(dir, "home.json.tmp"); assertTrue(blocked.mkdir())
        val id = m.room!!.fans.first().id
        assertTrue(m.edit { it.setFanPower(id, 0.9) })
        assertFalse(m.lastSaveSucceeded)
        m.clearError(); assertFalse(m.lastSaveSucceeded)
        m.setEnvironment(270.0, 4.5)
        assertFalse(m.lastSaveSucceeded)
        assertTrue(m.lastError!!.contains("保存失败"))
        assertFalse(m.retrySave())
        assertTrue(blocked.delete())
        assertTrue(m.retrySave()); assertTrue(m.lastSaveSucceeded)
        assertTrue(m.lastError!!.contains("重新保存"))
        val restored = model(dir)
        assertEquals(0.9, restored.room!!.fans.first().power)
        assertEquals(m.environment, restored.environment)
        assertEquals(m.currentHash(), restored.currentHash())
    }

    @Test fun failedSessionSaveDoesNotAnnounceSuccessDespiteSavedHome() {
        val dir = directory(); val m = model(dir)
        m.setEnvironment(90.0, 3.0)
        val blocked = File(dir, "home.json.session.json.tmp"); assertTrue(blocked.mkdir())
        m.setOrientation(180.0)
        assertEquals(180.0, DocumentCodec.decode(File(dir, "home.json").readText()).home.orientationDegrees)
        assertFalse(m.lastSaveSucceeded)
        assertTrue(m.lastError!!.contains("保存失败"))
        assertTrue(blocked.delete())
        assertTrue(m.retrySave()); assertTrue(m.lastSaveSucceeded)
        assertEquals(180.0, model(dir).home.orientationDegrees)
    }

    @Test fun settersReportSuccessfulSaveOnlyAfterDurablePersistence() {
        val dir = directory(); val m = model(dir)
        m.setEnvironment(90.0, 3.0)
        val originalBytes = File(dir, "home.json").readBytes()
        val blocked = File(dir, "home.json.tmp"); assertTrue(blocked.mkdir())
        m.setLocation(1.352, 103.82)
        assertFalse(m.lastSaveSucceeded); assertTrue(m.lastError!!.contains("保存失败"))
        m.setOrientation(90.0)
        assertFalse(m.lastSaveSucceeded); assertTrue(m.lastError!!.contains("保存失败"))
        m.setFloor(8)
        assertFalse(m.lastSaveSucceeded); assertTrue(m.lastError!!.contains("保存失败"))
        m.setEnvironment(270.0, 4.0)
        assertFalse(m.lastSaveSucceeded); assertTrue(m.lastError!!.contains("保存失败"))
        assertContentEquals(originalBytes, File(dir, "home.json").readBytes())
        assertTrue(blocked.delete())
        assertTrue(m.retrySave())
        assertEquals(m.document, model(dir).document)
        assertEquals(m.environment, model(dir).environment)
    }

    @Test fun corruptHomeIsPreservedByteForByteBeforeFallbackEdits() {
        val dir = directory()
        val original = byteArrayOf(0, 1, 2) + "不能解析的住宅原文".toByteArray(Charsets.UTF_8)
        val source = File(dir, "home.json"); source.writeBytes(original)
        val m = model(dir)
        val backup = File(dir, "home.json.recovery.json")
        assertContentEquals(original, source.readBytes())
        assertContentEquals(original, backup.readBytes())
        assertTrue(m.loadMessage!!.contains("恢复副本"))
        m.renameRoom(m.selectedRoomId, "重新建立的客厅")
        assertTrue(m.lastSaveSucceeded)
        assertEquals("重新建立的客厅", DocumentCodec.decode(source.readText()).home.rooms.first().name)
        assertContentEquals(original, backup.readBytes())
    }

    @Test fun unsupportedFutureSchemaIsPreservedBeforeFallbackSave() {
        val dir = directory()
        val future = """{"schemaVersion":99,"home":{"privateFutureField":"住宅原始数据"},"futureUnknown":[1,2,3]}""".toByteArray(Charsets.UTF_8)
        val source = File(dir, "home.json"); source.writeBytes(future)
        val m = model(dir)
        assertTrue(m.loadMessage!!.contains("v99"))
        assertContentEquals(future, File(dir, "home.json.recovery.json").readBytes())
        m.setEnvironment(90.0, 3.0)
        assertTrue(m.lastSaveSucceeded)
        assertEquals(2, DocumentCodec.decode(source.readText()).schemaVersion)
        assertContentEquals(future, File(dir, "home.json.recovery.json").readBytes())
    }

    @Test fun failedRecoveryBackupBlocksOverwriteUntilRetryCanPreserveOriginal() {
        val dir = directory()
        val original = """{"schemaVersion":99,"home":{"cannotRead":"原始数据"}}""".toByteArray(Charsets.UTF_8)
        val source = File(dir, "home.json"); source.writeBytes(original)
        val blocked = File(dir, "home.json.recovery.json"); assertTrue(blocked.mkdir())
        val m = model(dir)
        assertFalse(m.lastSaveSucceeded)
        assertTrue(m.loadMessage!!.contains("原文件已保留"))
        m.renameRoom(m.selectedRoomId, "本次编辑")
        assertFalse(m.lastSaveSucceeded)
        assertTrue(m.lastError!!.contains("保存失败"))
        assertContentEquals(original, source.readBytes())
        assertFalse(m.retrySave())
        assertContentEquals(original, source.readBytes())
        assertTrue(blocked.delete())
        assertTrue(m.retrySave()); assertTrue(m.lastSaveSucceeded)
        assertContentEquals(original, File(dir, "home.json.recovery.json").readBytes())
        assertEquals("本次编辑", DocumentCodec.decode(source.readText()).home.rooms.first().name)
    }

    @Test fun laterRecoveryDoesNotOverwriteEarlierRecoveryCopy() {
        val dir = directory()
        val source = File(dir, "home.json")
        val corrupt = "损坏的原文件".toByteArray(Charsets.UTF_8)
        source.writeBytes(corrupt); model(dir)
        val future = """{"schemaVersion":88,"home":{},"futureField":"第二份数据"}""".toByteArray(Charsets.UTF_8)
        source.writeBytes(future)
        val second = model(dir)
        assertContentEquals(corrupt, File(dir, "home.json.recovery.json").readBytes())
        assertContentEquals(future, File(dir, "home.json.recovery.1.json").readBytes())
        second.renameRoom(second.selectedRoomId, "新客厅")
        assertTrue(second.lastSaveSucceeded)
        assertContentEquals(corrupt, File(dir, "home.json.recovery.json").readBytes())
        assertContentEquals(future, File(dir, "home.json.recovery.1.json").readBytes())
    }

    @Test fun repositoryProtectsFutureDataWithoutPriorModelLoad() {
        val dir = directory()
        val original = """{"schemaVersion":55,"home":{},"raw":"完整原文"}""".toByteArray(Charsets.UTF_8)
        val repo = HomeRepository(dir); repo.file.writeBytes(original)
        repo.save(SampleData.sampleDocument())
        assertContentEquals(original, File(dir, "home.json.recovery.json").readBytes())
        assertEquals(HomeLoadState.Loaded(SampleData.sampleDocument()), repo.load())
    }

    @Test fun repeatedSaveFailureReusesVerifiedRecoveryCopy() {
        val dir = directory()
        File(dir, "home.json").writeText("broken document")
        val m = model(dir)
        val blocked = File(dir, "home.json.tmp"); assertTrue(blocked.mkdir())
        repeat(3) { assertFalse(m.retrySave()) }
        assertEquals(listOf("home.json.recovery.json"), dir.listFiles()!!.filter { it.name.contains(".recovery") }.map { it.name })
        assertTrue(blocked.delete()); assertTrue(m.retrySave())
    }

    @Test fun explicitDataDeletionAlsoRemovesPrivateRecoveryCopies() {
        val dir = directory()
        File(dir, "home.json").writeText("private corrupt home")
        val m = model(dir)
        m.renameRoom(m.selectedRoomId, "新家")
        assertTrue(File(dir, "home.json.recovery.json").isFile)
        m.deleteAllData()
        assertTrue(m.lastSaveSucceeded)
        assertFalse(File(dir, "home.json").exists())
        assertFalse(session(dir).exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".recovery") })
    }

    @Test fun normalizedScanImportPreservesRelativeGeometryAndExistingHomePlans() {
        val m = model()
        val originalRoom = m.room!!
        val originalPlan = m.saveScenario("原客厅方案")!!
        val polygon = RoomTemplate.Rectangle(4.0, 3.0).polygon
        val scan = Room(name = "扫描卧室", polygon = polygon, height = 2.9, purpose = RoomPurpose.bedroom).let { room ->
            room.copy(
                fans = listOf(Fan(position = Vec2(0.8, 0.8), yawDegrees = 45.0, power = 0.7)),
                obstacles = listOf(Obstacle(kind = ObstacleKind.wardrobe, center = Vec2(3.0, 2.0), size = Vec2(1.0, 0.8), rotationDegrees = 15.0, heightClass = HeightClass.tall)),
                targets = listOf(
                    Target(name = "床边", shape = TargetShape.Circle(Vec2(1.5, 1.5), 0.4), objective = TargetObjective.wantBreeze),
                    Target(name = "书桌", shape = TargetShape.Polygon(listOf(Vec2(2.4, 0.6), Vec2(3.2, 0.6), Vec2(3.2, 1.2), Vec2(2.4, 1.2))), objective = TargetObjective.avoidDirectDraft),
                ),
                openings = listOf(Opening(wallId = room.wallIds[0], kind = OpeningKind.window, offset = 0.5, width = 1.0, openFraction = 0.6,
                    locked = true, connects = listOf(ConnectionTarget.RoomRef(originalRoom.id)), exteriorObstruction = ObstructionLevel.partial)),
            )
        }
        val offset = Vec2(0.0, 5.0)
        m.importRoom(scan)
        val imported = m.room!!
        assertEquals(2, m.home.rooms.size)
        assertEquals(scan.id, m.selectedRoomId)
        assertEquals(originalRoom, m.home.rooms.first { it.id == originalRoom.id })
        assertEquals(scan.id, imported.id); assertEquals(scan.wallIds, imported.wallIds)
        assertEquals(scan.name, imported.name); assertEquals(scan.height, imported.height); assertEquals(scan.purpose, imported.purpose)
        assertEquals(scan.polygon.map { it + offset }, imported.polygon)
        assertEquals(scan.fans.map { it.copy(position = it.position + offset) }, imported.fans)
        assertEquals(scan.obstacles.map { it.copy(center = it.center + offset) }, imported.obstacles)
        assertEquals(scan.targets[0].copy(shape = TargetShape.Circle(Vec2(1.5, 6.5), 0.4)), imported.targets[0])
        assertEquals(scan.targets[1].copy(shape = TargetShape.Polygon(listOf(Vec2(2.4, 5.6), Vec2(3.2, 5.6), Vec2(3.2, 6.2), Vec2(2.4, 6.2)))), imported.targets[1])
        assertEquals(scan.openings.map { it.copy(connects = listOf(ConnectionTarget.RoomRef(scan.id), ConnectionTarget.Outdoor)) }, imported.openings)
        val retainedPlan = m.document.scenarios.single()
        assertEquals(originalPlan.copy(geometryRevision = m.document.geometryRevision), retainedPlan)
        assertTrue(m.canApplyScenario(retainedPlan)); assertTrue(m.lastSaveSucceeded)
    }

    @Test fun externalImportFromArbitraryOriginUsesFreeSpaceBeyondAllExistingRooms() {
        val m = model()
        m.createRoom(RoomTemplate.DEFAULT_RECTANGLE, "第二个房间", 4.0, 3.0, replaceCurrent = false)
        val existingRooms = m.home.rooms
        val localOrigin = Vec2(12.0, -8.0)
        val scan = Room(name = "外部房间", polygon = RoomTemplate.Rectangle(4.0, 3.0).polygon.map { it + localOrigin },
            fans = listOf(Fan(position = Vec2(13.0, -7.0), yawDegrees = 180.0)),
            targets = listOf(Target(name = "床边", shape = TargetShape.Circle(Vec2(14.0, -6.0), 0.5), objective = TargetObjective.wantBreeze)))
        m.importRoom(scan)
        val imported = m.room!!
        assertEquals(existingRooms, m.home.rooms.filter { it.id != scan.id })
        assertEquals(0.0, imported.polygon.minOf { it.x })
        assertEquals(9.0, imported.polygon.minOf { it.y })
        assertEquals(scan.fans.single().copy(position = Vec2(1.0, 10.0)), imported.fans.single())
        assertEquals(scan.targets.single().copy(shape = TargetShape.Circle(Vec2(2.0, 11.0), 0.5)), imported.targets.single())
        assertTrue(m.lastSaveSucceeded)
    }

    @Test fun rejectedInvalidImportsLeaveHomeSelectionAndPlansUnchanged() {
        val m = model()
        m.saveScenario("保留的方案")
        val before = m.document
        val selected = m.selectedRoomId
        val invalidTarget = Room(name = "目标在房间外", polygon = RoomTemplate.Rectangle(4.0, 3.0).polygon,
            targets = listOf(Target(name = "无效", shape = TargetShape.Circle(Vec2(99.0, 99.0), 0.5), objective = TargetObjective.wantBreeze)))
        val invalidRooms = listOf(invalidTarget, Room(name = "空房间", polygon = emptyList()),
            Room(name = "非有限坐标", polygon = RoomTemplate.Rectangle(4.0, 3.0).polygon.toMutableList().also { it[0] = Vec2(Double.NaN, 0.0) }),
            m.room!!)
        for (invalid in invalidRooms) {
            m.importRoom(invalid)
            assertEquals(before, m.document)
            assertEquals(selected, m.selectedRoomId)
            assertTrue(m.lastError!!.contains("房间数据无效"))
        }
    }

    @Test fun importedRoomDoesNotHidePersistenceFailure() {
        val dir = directory(); val m = model(dir)
        m.saveScenario("原方案")
        val originalDisk = File(dir, "home.json").readText()
        val blocked = File(dir, "home.json.tmp"); assertTrue(blocked.mkdir())
        val scan = Room(name = "扫描房间", polygon = RoomTemplate.Rectangle(4.0, 3.0).polygon)
        m.importRoom(scan)
        assertEquals(scan.id, m.selectedRoomId)
        assertEquals(2, m.home.rooms.size)
        assertFalse(m.lastSaveSucceeded)
        assertTrue(m.lastError!!.contains("保存失败"))
        assertEquals(originalDisk, File(dir, "home.json").readText())
        assertTrue(blocked.delete()); assertTrue(m.retrySave())
        assertEquals(m.document, model(dir).document)
    }
}
