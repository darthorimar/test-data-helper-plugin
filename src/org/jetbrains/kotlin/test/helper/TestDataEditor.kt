package org.jetbrains.kotlin.test.helper

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.pom.Navigatable
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.jetbrains.kotlin.test.helper.actions.ChooseAdditionalFileAction
import java.awt.Component
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.*
import java.util.stream.Collectors
import javax.swing.*

class TestDataEditor(
    private val baseEditor: TextEditor,
    splitEditors: List<FileEditor>,
    private val name: String = "Test Data"
) : UserDataHolderBase(), TextEditor {

    class State(
        val baseEditor: TextEditor,
        val splitEditors: List<FileEditor>,
        currentPreview: Int
    ) {
        var currentPreview: Int = currentPreview.takeIf { it in splitEditors.indices } ?: 0
            private set

        val currentPreviewEditor: FileEditor
            get() = splitEditors[currentPreview]

        val baseFileIsChosen: Boolean
            get() = baseEditor == splitEditors[currentPreview]

        fun chooseNewEditor(editor: FileEditor) {
            if (editor !in splitEditors) {
                // TODO: log
                return
            }
            currentPreview = splitEditors.indexOf(editor)
        }
    }

    // ------------------------------------- components -------------------------------------

    private val state: State = State(
        baseEditor,
        splitEditors,
        PropertiesComponent.getInstance().getValue(lastUsedPreviewPropertyName)?.toIntOrNull() ?: 0
    )

    private lateinit var editorViewMode: EditorViewMode

    private val splitter: JBSplitter by lazy {
        JBSplitter(false, 0.5f, 0.15f, 0.85f).apply {
            splitterProportionKey = splitterProportionKey
            firstComponent = baseEditor.component
            secondComponent = state.currentPreviewEditor.component
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
        createBuildActionGroup()
        return ActionManager
            .getInstance()
            .createActionToolbar(
                ActionPlaces.TEXT_EDITOR_WITH_PREVIEW,
                DefaultActionGroup(createPreviewActionGroup()),
                true
            )
    }

    private fun createPreviewActionGroup(): ActionGroup {
        return DefaultActionGroup(ChooseAdditionalFileAction(this, state))
    }

    fun updatePreviewEditor() {
        val viewMode = if (state.baseFileIsChosen) {
            EditorViewMode.OnlyBaseEditor
        } else {
            EditorViewMode.BaseAndAdditionalEditor
        }
        splitter.secondComponent = state.currentPreviewEditor.component
        editorViewMode = viewMode
        PropertiesComponent.getInstance()
            .setValue(
                lastUsedPreviewPropertyName,
                state.currentPreview.toString()
            )
        baseEditor.component.isVisible = true
        state.currentPreviewEditor.component.isVisible = editorViewMode == EditorViewMode.BaseAndAdditionalEditor
    }


    private fun createTestRunToolbar(): ActionToolbar {
        createBuildActionGroup()
        return ActionManager
            .getInstance()
            .createActionToolbar(
                ActionPlaces.TEXT_EDITOR_WITH_PREVIEW,
                DefaultActionGroup(GeneratedTestComboBoxAction(), currentGroup),
                true
            )
    }

    private inner class GeneratedTestComboBoxAction : ComboBoxAction() {
        override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
            return DefaultActionGroup()
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val box = ComboBox(DefaultComboBoxModel(debugAndRun.toTypedArray()))
            box.addActionListener { changeDebugAndRun(box.item) }
            box.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val originalComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val order = debugAndRun.indexOf(value)
                    text = methodsClassNames[order]
                    return originalComponent
                }
            }
            box.putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
            val label = JBLabel("Tests: ")
            panel.add(label)
            panel.add(box)
            return panel
        }
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

    private fun createBuildActionGroup() {
        val file = baseEditor.file ?: return
        val name = file.nameWithoutExtension
        val truePath = file.path
        val path = baseEditor.editor.project!!.basePath
        val testMethods = collectMethods(name, path!!, truePath)
        val ex = TestRunLineMarkerProvider()
        for (testMethod in testMethods) {
            val identifier = Arrays
                .stream(testMethod.children)
                .filter { p: PsiElement? -> p is PsiIdentifier }
                .findFirst()
                .orElse(null) ?: continue
            val info = ex.getInfo(identifier) ?: continue
            var allActions = info.actions
            if (allActions.isEmpty()) {
                continue
            }
            allActions = Arrays.copyOf(allActions, 2)
            val group: List<AnAction> = Arrays.stream(allActions).map {
                object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        val dataContext = SimpleDataContext.builder().apply {
                            val newLocation = PsiLocation.fromPsiElement(identifier)
                            setParent(e.dataContext)
                            add(Location.DATA_KEY, newLocation)
                        }.build()

                        val newEvent = AnActionEvent(
                            e.inputEvent,
                            dataContext,
                            e.place,
                            e.presentation,
                            e.actionManager,
                            e.modifiers
                        )
                        it.actionPerformed(newEvent)
                    }

                    override fun update(e: AnActionEvent) {
                        it.update(e)
                        e.presentation.isEnabledAndVisible = true
                    }
                }
            }.collect(Collectors.toList())
            val first = group[0]
            val newAction1: AnAction = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    first.actionPerformed(e)
                }

                override fun update(e: AnActionEvent) {
                    first.update(e)
                    e.presentation.icon = AllIcons.Actions.Execute
                }
            }
            val second = group[1]
            val newAction2: AnAction = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    second.actionPerformed(e)
                }

                override fun update(e: AnActionEvent) {
                    second.update(e)
                    e.presentation.icon = AllIcons.Actions.StartDebugger
                }
            }
            val finalActions: MutableList<AnAction> = ArrayList()
            finalActions.add(newAction1)
            finalActions.add(newAction2)
            debugAndRun.add(finalActions)

            val topLevelClass = testMethod.parentsOfType<PsiClass>().last()
            methodsClassNames.add(topLevelClass.name!!)
        }
    }

    // ------------------------------------- unsorted -------------------------------------

    private val debugAndRun: MutableList<List<AnAction>> = ArrayList()
    private var currentChosenGroup = 0
    private val currentGroup = DefaultActionGroup()

    private val methodsClassNames: MutableList<String> = ArrayList()

    private val listenersGenerator: ListenersMultimap = ListenersMultimap()

    private fun collectMethods(baseName: String, path: String, truePath: String): List<PsiMethod> {
        val project = editor.project
        val cache = PsiShortNamesCache.getInstance(project)
        val targetMethodName = "test" + Character.toUpperCase(baseName[0]) + baseName.substring(1)
        val psiMethods = cache.getMethodsByName(
            targetMethodName, GlobalSearchScope.allScope(
                project!!
            )
        )
        val methods = Arrays
            .stream(psiMethods)
            .filter { method: PsiMethod -> method.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }
            .collect(Collectors.toList())
        val findingMethods: MutableList<PsiMethod> = ArrayList()
        for (method in methods) {
            val classPath = method.containingClass?.extractTestMetadataValue() ?: continue
            val methodPathPart = method?.extractTestMetadataValue() ?: continue
            val methodPath = "$path/$classPath/$methodPathPart"
            println("$methodPath | $truePath")
            if (methodPath == truePath) {
                findingMethods.add(method)
            }
        }
        return findingMethods
    }

    private fun PsiModifierListOwner?.extractTestMetadataValue(): String? {
        return this?.getAnnotation("org.jetbrains.kotlin.test.TestMetadata")
            ?.parameterList
            ?.attributes
            ?.get(0)
            ?.literalValue
    }

    override fun setState(state: FileEditorState) {
        if (state !is MyFileEditorState) return
        if (state.firstState != null) {
            baseEditor.setState(state.firstState)
        }
        if (state.secondState != null) {
            this@TestDataEditor.state.currentPreviewEditor.setState(state.secondState)
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
        val previewEditor = state.currentPreviewEditor
        previewEditor.addPropertyChangeListener(listener)
        val delegate = listenersGenerator.addListenerAndGetDelegate(listener)
        baseEditor.addPropertyChangeListener(delegate)
        previewEditor.addPropertyChangeListener(delegate)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        baseEditor.removePropertyChangeListener(listener)
        val previewEditor = state.currentPreviewEditor
        previewEditor.removePropertyChangeListener(listener)
        val delegate = listenersGenerator.removeListenerAndGetDelegate(listener)
        if (delegate != null) {
            baseEditor.removePropertyChangeListener(delegate)
            previewEditor.removePropertyChangeListener(delegate)
        }
    }

    fun changeDebugAndRun(item: List<AnAction>) {
        currentChosenGroup = debugAndRun.indexOf(item)
        currentGroup.removeAll()
        val current = debugAndRun[currentChosenGroup]
        currentGroup.addAll(current)
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
        state.splitEditors.forEach { it.dispose() }
    }

    override fun selectNotify() {
        baseEditor.selectNotify()
        state.splitEditors.forEach { it.selectNotify() }
    }

    override fun deselectNotify() {
        baseEditor.deselectNotify()
        state.splitEditors.forEach { it.deselectNotify() }
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return baseEditor.preferredFocusedComponent
    }

    override fun getName(): String {
        return name
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        return MyFileEditorState(editorViewMode, baseEditor.getState(level), state.currentPreviewEditor.getState(level))
    }

    override fun isModified(): Boolean {
        return baseEditor.isModified || state.splitEditors.any { it.isModified }
    }

    override fun isValid(): Boolean {
        return baseEditor.isValid && state.splitEditors.all { it.isValid }
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
