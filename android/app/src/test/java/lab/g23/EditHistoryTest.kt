package lab.g23

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    private fun state(n: Int) = JSONObject().put("exposure", n)

    @Test
    fun undoRedoRestoreAndBranch() {
        val history = EditHistory(state(0))
        history.commit(state(1))
        history.commit(state(2))
        assertEquals(1, history.undo().getInt("exposure"))
        assertEquals(2, history.redo().getInt("exposure"))
        history.undo()
        history.commit(state(9))
        assertEquals(9, history.redo().getInt("exposure"))
        val restored = EditHistory(state(9), history.serialize())
        assertEquals(1, restored.undo().getInt("exposure"))
        assertEquals(0, restored.undo().getInt("exposure"))
    }

    @Test
    fun currentDoesNotMutateSnapshot() {
        val h = EditHistory(state(0))
        h.current().put("exposure", 10)
        assertEquals(0, h.current().getInt("exposure"))
    }

    @Test
    fun staleHistoryDoesNotReplaceCurrentParameters() {
        val h = EditHistory(state(0))
        h.commit(state(1))
        val restored = EditHistory(state(7), h.serialize())
        assertEquals(7, restored.current().getInt("exposure"))
    }

    @Test
    fun corruptHistoryPreservesInitialState() {
        val persisted = """{"schema_version":"1.0","states":[42,{"exposure":7}],"cursor":1}"""
        assertEquals(7, EditHistory(state(7), persisted).current().getInt("exposure"))
    }
}
