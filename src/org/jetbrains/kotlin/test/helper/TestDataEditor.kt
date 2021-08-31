package org.jetbrains.kotlin.test.helper

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.pom.Navigatable
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import org.jetbrains.kotlin.test.helper.actions.ChooseAdditionalFileAction
import org.jetbrains.kotlin.test.helper.actions.GeneratedTestComboBoxAction
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.*
import javax.swing.*

class TestDataEditor(
    private val baseEditor: TextEditor,
    splitEditors: List<FileEditor>,
    private val name: String = "Test Data"
) : UserDataHolderBase(), TextEditor {

    // ------------------------------------- components -------------------------------------

    private val previewEditorState: PreviewEditorState = PreviewEditorState(
        baseEditor,
        splitEditors,
        PropertiesComponent.getInstance().getValue(lastUsedPreviewPropertyName)?.toIntOrNull() ?: 0
    )

    private val runTestBoxState = RunTestBoxState(baseEditor.editor.project!!).also {
        it.initialize(baseEditor.file)
    }

    private lateinit var editorViewMode: EditorViewMode

    private val splitter: JBSplitter by lazy {
        JBSplitter(false, 0.5f, 0.15f, 0.85f).apply {
            splitterProportionKey = splitterProportionKey
            firstComponent = baseEditor.component
            secondComponent = previewEditorState.currentPreview.component
            dividerWidth = 3
        }
    }

    private val myComponent: JComponent by lazy {
        JBUI.Panels.simplePanel(splitter).addToTop(myToolbarWrapper).also {
            updatePreviewEditor()
        }
    }

    private val myToolbarWrapper: SplitEditorToolbar by lazy {
        fun ActionToolbar.updateConfig() {
            setTargetComponent(splitter)
            setReservePlaceAutoPopupIcon(false)
        }

        val leftToolbar = createFileChooserToolbar().apply { updateConfig() }
        val rightToolbar = createTestRunToolbar().apply { updateConfig() }
        SplitEditorToolbar(leftToolbar, rightToolbar)
    }

    enum class EditorViewMode {
        OnlyBaseEditor,
        BaseAndAdditionalEditor;
    }

    private fun createFileChooserToolbar(): ActionToolbar {
        return ActionManager
            .getInstance()
            .createActionToolbar(
                ActionPlaces.TEXT_EDITOR_WITH_PREVIEW,
                DefaultActionGroup(createPreviewActionGroup()),
                true
            )
    }

    private fun createPreviewActionGroup(): ActionGroup {
        return DefaultActionGroup(ChooseAdditionalFileAction(this, previewEditorState))
    }

    fun updatePreviewEditor() {
        val viewMode = if (previewEditorState.baseFileIsChosen) {
            EditorViewMode.OnlyBaseEditor
        } else {
            EditorViewMode.BaseAndAdditionalEditor
        }
        splitter.secondComponent = previewEditorState.currentPreview.component
        editorViewMode = viewMode
        PropertiesComponent.getInstance()
            .setValue(
                lastUsedPreviewPropertyName,
                previewEditorState.currentPreviewIndex.toString()
            )
        baseEditor.component.isVisible = true
        previewEditorState.currentPreview.component.isVisible = editorViewMode == EditorViewMode.BaseAndAdditionalEditor
    }


    private fun createTestRunToolbar(): ActionToolbar {
        return ActionManager
            .getInstance()
            .createActionToolbar(
                ActionPlaces.TEXT_EDITOR_WITH_PREVIEW,
                DefaultActionGroup(GeneratedTestComboBoxAction(runTestBoxState), runTestBoxState.currentGroup),
                true
            )
    }

    // ------------------------------------- actions -------------------------------------

    private inner class DoublingEventListenerDelegate(private val myDelegate: PropertyChangeListener) : PropertyChangeListener {
        override fun propertyChange(evt: PropertyChangeEvent) {
            myDelegate.propertyChange(
                PropertyChangeEvent(this, evt.propertyName, evt.oldValue, evt.newValue)
            )
        }
    }

    private inner class ListenersMultimap {
        private val myMap: MutableMap<PropertyChangeListener, Pair<Int, DoublingEventListenerDelegate>> = HashMap()

        fun addListenerAndGetDelegate(listener: PropertyChangeListener): DoublingEventListenerDelegate {
            if (!myMap.containsKey(listener)) {
                myMap[listener] =
                    Pair.create(
                        1,
                        DoublingEventListenerDelegate(listener)
                    )
            } else {
                val oldPair = myMap[listener]!!
                myMap[listener] =
                    Pair.create(
                        oldPair.getFirst() + 1,
                        oldPair.getSecond()
                    )
            }
            return myMap[listener]!!.getSecond()
        }

        fun removeListenerAndGetDelegate(listener: PropertyChangeListener): DoublingEventListenerDelegate? {
            val oldPair = myMap[listener] ?: return null
            if (oldPair.getFirst() == 1) {
                myMap.remove(listener)
            } else {
                myMap[listener] =
                    Pair.create(
                        oldPair.getFirst() - 1,
                        oldPair.getSecond()
                    )
            }
            return oldPair.getSecond()
        }
    }

    // ------------------------------------- unsorted -------------------------------------
    private val listenersGenerator: ListenersMultimap = ListenersMultimap()

    override fun setState(state: FileEditorState) {
        if (state !is MyFileEditorState) return
        if (state.firstState != null) {
            baseEditor.setState(state.firstState)
        }
        if (state.secondState != null) {
            this@TestDataEditor.previewEditorState.currentPreview.setState(state.secondState)
        }
        if (state.splitLayout != null) {
            editorViewMode = state.splitLayout
            invalidateLayout()
        }
    }

    private fun invalidateLayout() {
        updatePreviewEditor()
        myToolbarWrapper.refresh()
        myComponent.repaint()
        val focusComponent = preferredFocusedComponent
        if (focusComponent != null) {
            IdeFocusManager.findInstanceByComponent(focusComponent).requestFocus(focusComponent, true)
        }
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        baseEditor.addPropertyChangeListener(listener)
        val previewEditor = previewEditorState.currentPreview
        previewEditor.addPropertyChangeListener(listener)
        val delegate = listenersGenerator.addListenerAndGetDelegate(listener)
        baseEditor.addPropertyChangeListener(delegate)
        previewEditor.addPropertyChangeListener(delegate)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        baseEditor.removePropertyChangeListener(listener)
        val previewEditor = previewEditorState.currentPreview
        previewEditor.removePropertyChangeListener(listener)
        val delegate = listenersGenerator.removeListenerAndGetDelegate(listener)
        if (delegate != null) {
            baseEditor.removePropertyChangeListener(delegate)
            previewEditor.removePropertyChangeListener(delegate)
        }
    }

    class MyFileEditorState(
        val splitLayout: EditorViewMode?,
        val firstState: FileEditorState?,
        val secondState: FileEditorState?
    ) : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
            return (otherState is MyFileEditorState
                    && (firstState == null || firstState.canBeMergedWith(otherState.firstState!!, level))
                    && (secondState == null || secondState.canBeMergedWith(otherState.secondState!!, level)))
        }
    }

    // ------------------------------------- default methods -------------------------------------

    override fun getComponent(): JComponent {
        return myComponent
    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? {
        return baseEditor.backgroundHighlighter
    }

    override fun getCurrentLocation(): FileEditorLocation? {
        return baseEditor.currentLocation
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        return baseEditor.structureViewBuilder
    }

    override fun dispose() {
        Disposer.dispose(baseEditor)
        previewEditorState.previewEditors.forEach { it.dispose() }
    }

    override fun selectNotify() {
        baseEditor.selectNotify()
        previewEditorState.previewEditors.forEach { it.selectNotify() }
    }

    override fun deselectNotify() {
        baseEditor.deselectNotify()
        previewEditorState.previewEditors.forEach { it.deselectNotify() }
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return baseEditor.preferredFocusedComponent
    }

    override fun getName(): String {
        return name
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        return MyFileEditorState(editorViewMode, baseEditor.getState(level), previewEditorState.currentPreview.getState(level))
    }

    override fun isModified(): Boolean {
        return baseEditor.isModified || previewEditorState.previewEditors.any { it.isModified }
    }

    override fun isValid(): Boolean {
        return baseEditor.isValid && previewEditorState.previewEditors.all { it.isValid }
    }

    private val lastUsedPreviewPropertyName: String
        get() = name + "LastUsedPreview"

    override fun getFile(): VirtualFile? {
        return baseEditor.file
    }

    override fun getEditor(): Editor {
        return baseEditor.editor
    }

    override fun canNavigateTo(navigatable: Navigatable): Boolean {
        return baseEditor.canNavigateTo(navigatable)
    }

    override fun navigateTo(navigatable: Navigatable) {
        baseEditor.navigateTo(navigatable)
    }
}