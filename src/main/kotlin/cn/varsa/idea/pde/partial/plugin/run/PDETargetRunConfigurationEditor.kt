package cn.varsa.idea.pde.partial.plugin.run

import cn.varsa.idea.pde.partial.plugin.dom.config.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles.message
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.execution.ui.*
import com.intellij.icons.*
import com.intellij.ide.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.*
import com.intellij.openapi.ui.*
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.util.execution.*
import com.intellij.util.ui.*
import java.awt.*
import javax.swing.*

class PDETargetRunConfigurationEditor(configuration: PDETargetRunConfiguration) :
  SettingsEditor<PDETargetRunConfiguration>(), PanelWithAnchor {
  private var myAnchor: JComponent? = null

  private val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 5, true, false))

  private val productField = ComboBox<String>().apply {
    renderer = ColoredListCellRendererWithSpeedSearch.stringRender()
    ComboboxSpeedSearch.installOn(this).setClearSearchOnNavigateNoMatch(true)
  }
  private val applicationField = ComboBox<String>().apply {
    renderer = ColoredListCellRendererWithSpeedSearch.stringRender()
    ComboboxSpeedSearch.installOn(this).setClearSearchOnNavigateNoMatch(true)
  }
  private val splashBundlePathField = JBTextField()
  private val dataDirectoryField = JBTextField()

  private val productComponent =
    LabeledComponent.create(productField, message("run.local.config.tab.configuration.product"), BorderLayout.WEST)
  private val applicationComponent = LabeledComponent.create(
    applicationField, message("run.local.config.tab.configuration.application"), BorderLayout.WEST
  )
  private val splashBundlePathPanel = JPanel(BorderLayout()).apply {
    add(splashBundlePathField, BorderLayout.CENTER)
    val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    val helpIcon = JLabel(AllIcons.General.ContextHelp)
    HelpTooltip()
      .setDescription(message("run.local.config.tab.configuration.splashBundleHelp"))
      .setTitle(message("run.local.config.tab.configuration.splashBundleName"))
      .installOn(helpIcon)
    infoPanel.add(helpIcon)
    add(infoPanel, BorderLayout.EAST)
  }

  private val splashBundlePathComponent = LabeledComponent.create(
    splashBundlePathPanel, message("run.local.config.tab.configuration.splashBundleName"), BorderLayout.WEST
  )
  private val dataDirectoryComponent = LabeledComponent.create(
    dataDirectoryField, message("run.local.config.tab.configuration.dataDirectory"), BorderLayout.WEST
  )

  private val jrePath = JrePathEditor(DefaultJreSelector.projectSdk(configuration.project))
  private val javaParameters = CommonJavaParametersPanel().apply { preferredSize = null }

  private val additionalClasspath =
    RawCommandLineEditor(ParametersListUtil.COLON_LINE_PARSER, ParametersListUtil.COLON_LINE_JOINER)
  private val additionalClasspathComponent = LabeledComponent.create(
    additionalClasspath, message("run.local.config.tab.configuration.classpath"), BorderLayout.WEST
  )

  private val moduleList = CheckBoxList<String>()

  // Dual-list: available vs excluded bundles
  private val availableList = JBList<String>()
  private val excludedList = JBList<String>()
  private val availableModel = javax.swing.DefaultListModel<String>()
  private val excludedModel = javax.swing.DefaultListModel<String>()
  private var allBundles: List<String> = emptyList()
  private val excludedSet = linkedSetOf<String>()
  private val availableFilterField = com.intellij.ui.SearchTextField()
  private val availableTitle = JBLabel()
  private val excludedTitle = JBLabel()

  private val cleanRuntimeDir = JBCheckBox(message("run.remote.config.tab.wishes.cleanRuntimeDir"))

  init {
    panel.add(productComponent)
    panel.add(applicationComponent)
    panel.add(splashBundlePathComponent)
    panel.add(dataDirectoryComponent)
    panel.add(JSeparator())
    panel.add(jrePath)
    panel.add(javaParameters)
    panel.add(JSeparator())
    panel.add(additionalClasspathComponent)

    panel.add(targetModulePanel())
    panel.add(excludedBundlesPanel())
    panel.add(cleanRuntimeDir)

    additionalClasspath.attachLabel(additionalClasspathComponent.label)
    CommonJavaParametersPanel.addMacroSupport(additionalClasspath.editorField)

    val pdeModules = configuration.project.allPDEModules().filter { PDEFacet.getInstance(it) != null }
    moduleList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    moduleList.setItems(pdeModules.map { module -> module.name }, null)
    if (moduleList.itemsCount > 0)
      moduleList.allItems.forEach{ moduleList.setItemSelected(it, configuration.targetModules == null || it in configuration.targetModules!!) }

    // Init dual-list (available/excluded)
    allBundles = cn.varsa.idea.pde.partial.plugin.config.BundleManagementService
      .getInstance(configuration.project)
      .getBundles()
      .map { it.bundleSymbolicName }
      .distinct()
      .sorted()
    excludedSet.clear()
    configuration.excludedBundles?.let { excludedSet.addAll(it) }
    availableList.model = availableModel
    excludedList.model = excludedModel
    availableList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    excludedList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    com.intellij.ui.ListSpeedSearch(availableList)
    com.intellij.ui.ListSpeedSearch(excludedList)
    refreshDualLists("")
    availableFilterField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
      override fun textChanged(e: javax.swing.event.DocumentEvent) {
        refreshDualLists(availableFilterField.text)
      }
    })
    // Double-click / Enter to move items
    availableList.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        if (e.clickCount == 2) excludeSelected()
      }
    })
    excludedList.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        if (e.clickCount == 2) includeSelected()
      }
    })
    availableList.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "exclude")
    availableList.actionMap.put("exclude", object : javax.swing.AbstractAction() {
      override fun actionPerformed(e: java.awt.event.ActionEvent?) = excludeSelected()
    })
    excludedList.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "include")
    excludedList.actionMap.put("include", object : javax.swing.AbstractAction() {
      override fun actionPerformed(e: java.awt.event.ActionEvent?) = includeSelected()
    })

    panel.updateUI()

    myAnchor = UIUtil.mergeComponentsWithAnchor(
      productComponent,
      applicationComponent,
      dataDirectoryComponent,
      jrePath,
      javaParameters,
      splashBundlePathComponent,
      additionalClasspathComponent
    )
  }

  private fun targetModulePanel(): DialogPanel {
    val selectAllAction = object : AnAction(
      message("run.local.config.tab.configuration.targetModules.selectAll"),
      message("run.local.config.tab.configuration.targetModules.selectAllModules"), AllIcons.Actions.Selectall // Use standard icon
    ) {
      override fun actionPerformed(e: AnActionEvent) {
        if (moduleList.itemsCount > 0) moduleList.allItems.forEach{ moduleList.setItemSelected(it, true) }
      }

      override fun update(e: AnActionEvent) {
        // Enable only if there are items and not all are already selected
        e.presentation.isEnabled = moduleList.allItems.any { !moduleList.isItemSelected(it) }
      }

      override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
      }
    }

    val unselectAllAction = object : AnAction(
      message("run.local.config.tab.configuration.targetModules.unselectAll"),
      message("run.local.config.tab.configuration.targetModules.unselectAllModules"), AllIcons.Actions.Unselectall // Use standard icon
    ) {
      override fun actionPerformed(e: AnActionEvent) {
        moduleList.allItems.forEach{ moduleList.setItemSelected(it, false) }
      }

      override fun update(e: AnActionEvent) {
        // Enable only if there is at least one item selected
        e.presentation.isEnabled = moduleList.allItems.any { moduleList.isItemSelected(it) }
      }

      override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
      }
    }

    val actionGroup = DefaultActionGroup().apply {
      add(selectAllAction)
      add(unselectAllAction)
    }
    val actionToolbar = ActionManager.getInstance().createActionToolbar(
      ActionPlaces.TOOLBAR,
      actionGroup,
      true // true for horizontal toolbar
    )
    actionToolbar.targetComponent = moduleList
    return panel {
      collapsibleGroup(message("run.local.config.tab.configuration.targetModules")) {
        row {
          cell(actionToolbar.component)
        }

        row {
          resizableRow().scrollCell(moduleList).align(com.intellij.ui.dsl.builder.Align.FILL)
        }
      }
    }
  }

  private fun excludedBundlesPanel(): DialogPanel {
    val excludeAction = object : AnAction(
      message("run.local.config.tab.configuration.excludedBundles.exclude"),
      message("run.local.config.tab.configuration.excludedBundles.exclude"), AllIcons.Actions.Forward
    ) {
      override fun actionPerformed(e: AnActionEvent) = excludeSelected()
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = !availableList.isSelectionEmpty }
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    val includeAction = object : AnAction(
      message("run.local.config.tab.configuration.excludedBundles.include"),
      message("run.local.config.tab.configuration.excludedBundles.include"), AllIcons.Actions.Back
    ) {
      override fun actionPerformed(e: AnActionEvent) = includeSelected()
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = !excludedList.isSelectionEmpty }
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    val excludeVisibleAction = object : AnAction(
      message("run.local.config.tab.configuration.excludedBundles.excludeVisible"),
      message("run.local.config.tab.configuration.excludedBundles.excludeVisible"), AllIcons.Actions.Selectall
    ) {
      override fun actionPerformed(e: AnActionEvent) = excludeVisible()
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = availableModel.size() > 0 }
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    val includeAllAction = object : AnAction(
      message("run.local.config.tab.configuration.excludedBundles.includeAll"),
      message("run.local.config.tab.configuration.excludedBundles.includeAll"), AllIcons.Actions.Unselectall
    ) {
      override fun actionPerformed(e: AnActionEvent) = includeAll()
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = excludedModel.size() > 0 }
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    val actionGroup = DefaultActionGroup().apply {
      add(excludeAction)
      add(includeAction)
      addSeparator()
      add(excludeVisibleAction)
      add(includeAllAction)
    }
    val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actionGroup, true)

    val availablePanel = JPanel(BorderLayout()).apply {
      val top = JPanel(BorderLayout(8, 0))
      top.add(availableTitle, BorderLayout.WEST)
      availableFilterField.textEditor.emptyText.text = message("run.local.config.tab.configuration.excludedBundles.search")
      top.add(availableFilterField, BorderLayout.CENTER)
      add(top, BorderLayout.NORTH)
      add(JBScrollPane(availableList), BorderLayout.CENTER)
    }
    val excludedPanel = JPanel(BorderLayout()).apply {
      add(excludedTitle, BorderLayout.NORTH)
      add(JBScrollPane(excludedList), BorderLayout.CENTER)
    }
    val listsContainer = JPanel(GridLayout(1, 2, 8, 0)).apply {
      add(availablePanel)
      add(excludedPanel)
    }

    return panel {
      collapsibleGroup(message("run.local.config.tab.configuration.excludedBundles")) {
        row { cell(actionToolbar.component) }
        row { resizableRow().cell(listsContainer).align(com.intellij.ui.dsl.builder.Align.FILL) }
      }
    }
  }

  override fun resetEditorFrom(configuration: PDETargetRunConfiguration) {
    javaParameters.reset(configuration)
    jrePath.setPathOrName(configuration.alternativeJrePath, configuration.isAlternativeJrePathEnabled)

    val managementService = ExtensionPointManagementService.getInstance(configuration.project)
    productField.apply {
      removeAllItems()
      addItem("")
      managementService.getProducts().sorted().forEach(this::addItem)
      item = configuration.product
    }
    applicationField.apply {
      removeAllItems()
      managementService.getApplications().sorted().forEach(this::addItem)
      item = configuration.application
    }
    splashBundlePathField.text = configuration.splashBundlePath
    dataDirectoryField.text = configuration.dataDirectory

    configuration.mainClassName = "org.eclipse.equinox.launcher.Main"
    cleanRuntimeDir.isSelected = configuration.cleanRuntimeDir
    additionalClasspath.text = configuration.additionalClasspath

    val pdeModules = configuration.project.allPDEModules().filter { PDEFacet.getInstance(it) != null }
    moduleList.clear()
    moduleList.setItems(pdeModules.map { module -> module.name }, null)
    if (moduleList.itemsCount > 0)
      moduleList.allItems.forEach{ moduleList.setItemSelected(it, configuration.targetModules == null || it in configuration.targetModules!!) }

    // Reset dual-list state
    allBundles = cn.varsa.idea.pde.partial.plugin.config.BundleManagementService
      .getInstance(configuration.project)
      .getBundles()
      .map { it.bundleSymbolicName }
      .distinct()
      .sorted()
    excludedSet.clear()
    configuration.excludedBundles?.let { excludedSet.addAll(it) }
    refreshDualLists(availableFilterField.text)
  }

  override fun applyEditorTo(configuration: PDETargetRunConfiguration) {
    javaParameters.applyTo(configuration)
    configuration.alternativeJrePath = jrePath.jrePathOrName
    configuration.isAlternativeJrePathEnabled = jrePath.isAlternativeJreSelected

    configuration.product = productField.item
    configuration.application = applicationField.item
    configuration.splashBundlePath = splashBundlePathField.text
    configuration.dataDirectory = dataDirectoryField.text

    configuration.mainClassName = "org.eclipse.equinox.launcher.Main"
    configuration.cleanRuntimeDir = cleanRuntimeDir.isSelected
    configuration.additionalClasspath = additionalClasspath.text

    val selectedModules =
      moduleList.allItems.filter { moduleList.isItemSelected(it) }
    configuration.targetModules =
      if (selectedModules.size == moduleList.itemsCount) null
      else selectedModules.toSet()

    configuration.excludedBundles = excludedSet.toSet()
  }

  override fun createEditor(): JComponent = panel
  private fun refreshDualLists(query: String) {
    val filter = query.trim()
    val availableItems = allBundles.asSequence()
      .filter { it !in excludedSet }
      .filter { filter.isEmpty() || it.contains(filter, ignoreCase = true) }
      .toList()
    availableModel.removeAllElements()
    availableItems.forEach { availableModel.addElement(it) }
    excludedModel.removeAllElements()
    excludedSet.forEach { excludedModel.addElement(it) }
    availableTitle.text = message("run.local.config.tab.configuration.excludedBundles.available") + " (" + availableModel.size() + ")"
    excludedTitle.text = message("run.local.config.tab.configuration.excludedBundles.excluded") + " (" + excludedModel.size() + ")"
  }

  private fun excludeSelected() {
    val toMove = availableList.selectedValuesList?.toList() ?: emptyList()
    if (toMove.isEmpty()) return
    excludedSet.addAll(toMove)
    refreshDualLists(availableFilterField.text)
  }

  private fun includeSelected() {
    val toMove = excludedList.selectedValuesList?.toList() ?: emptyList()
    if (toMove.isEmpty()) return
    excludedSet.removeAll(toMove.toSet())
    refreshDualLists(availableFilterField.text)
  }

  private fun excludeVisible() {
    val count = availableModel.size()
    if (count == 0) return
    val items = (0 until count).map { availableModel.getElementAt(it) }
    excludedSet.addAll(items)
    refreshDualLists(availableFilterField.text)
  }

  private fun includeAll() {
    if (excludedSet.isEmpty()) return
    excludedSet.clear()
    refreshDualLists(availableFilterField.text)
  }
  override fun getAnchor(): JComponent? = myAnchor
  override fun setAnchor(anchor: JComponent?) {
    myAnchor = anchor

    productComponent.anchor = anchor
    applicationComponent.anchor = anchor
    splashBundlePathComponent.anchor = anchor
    dataDirectoryComponent.anchor = anchor
    jrePath.anchor = anchor
    javaParameters.anchor = anchor
    additionalClasspathComponent.anchor = anchor
  }
}
