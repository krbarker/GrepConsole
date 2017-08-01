package krasa.grepconsole.grep;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.*;
import com.intellij.execution.ui.layout.impl.RunnerLayoutUiImpl;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import krasa.grepconsole.filter.GrepCopyingFilter;
import krasa.grepconsole.grep.gui.GrepPanel;
import krasa.grepconsole.grep.listener.GrepCopyingFilterAsyncListener;
import krasa.grepconsole.grep.listener.GrepCopyingFilterListener;
import krasa.grepconsole.grep.listener.GrepCopyingFilterSyncListener;
import krasa.grepconsole.grep.listener.Mode;
import krasa.grepconsole.model.Profile;
import krasa.grepconsole.plugin.GrepConsoleApplicationComponent;
import krasa.grepconsole.plugin.ServiceManager;
import krasa.grepconsole.utils.Utils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.OutputStream;
import java.util.UUID;

public class OpenGrepConsoleAction extends DumbAwareAction {
	public OpenGrepConsoleAction() {
	}

	public OpenGrepConsoleAction(@Nullable String text, @Nullable String description, @Nullable Icon icon) {
		super(text, description, icon);
	}

	@Override
	public void actionPerformed(AnActionEvent e) {
		Project eventProject = getEventProject(e);
		ConsoleViewImpl originalConsoleView = (ConsoleViewImpl) getConsoleView(e);
		String expression = getExpression(e);
		try {
			PinnedGrepsReopener.enabled = false;
			createGrepConsole(eventProject, originalConsoleView, null, expression, UUID.randomUUID().toString());
		} finally {
			PinnedGrepsReopener.enabled = true;
		}

	}

	public ConsoleViewImpl createGrepConsole(Project project, ConsoleViewImpl originalConsoleView, @Nullable GrepModel grepModel, @Nullable String expression, String consoleUUID) {
		if (grepModel != null) {
			expression = grepModel.getExpression();
		}

		final GrepCopyingFilter copyingFilter = ServiceManager.getInstance().getCopyingFilter(originalConsoleView);
		if (copyingFilter == null) {
			throw new IllegalStateException("Console not supported: " + originalConsoleView);
		}
		RunContentDescriptor runContentDescriptor = getRunContentDescriptor(project);
		RunnerLayoutUi runnerLayoutUi = getRunnerLayoutUi(project, originalConsoleView);
		LightProcessHandler myProcessHandler = new LightProcessHandler();
		Profile profile = GrepConsoleApplicationComponent.getInstance().getProfile();
		final GrepCopyingFilterListener copyingListener;
		Mode mode = Mode.SYNC;
		if (Mode.SYNC == mode) {
			copyingListener = new GrepCopyingFilterSyncListener(myProcessHandler, project, profile);
		} else {
			copyingListener = new GrepCopyingFilterAsyncListener(myProcessHandler, project, profile);
		}


		ConsoleViewImpl newConsole = (ConsoleViewImpl) createConsole(project, myProcessHandler);
		final GrepPanel quickFilterPanel = new GrepPanel(originalConsoleView, newConsole, copyingListener, grepModel, expression, runnerLayoutUi);


		DefaultActionGroup actions = new DefaultActionGroup();
		String parentConsoleUUID = getConsoleUUID(originalConsoleView);
		PinAction pinAction = new PinAction(project, quickFilterPanel, runContentDescriptor, parentConsoleUUID, consoleUUID);
		actions.add(pinAction);

		final MyJPanel consolePanel = createConsolePanel(runnerLayoutUi, newConsole, actions, quickFilterPanel, consoleUUID);
		for (AnAction action : newConsole.createConsoleActions()) {
			actions.add(action);
		}

		final Content tab = runnerLayoutUi.createContent(getContentType(runnerLayoutUi), consolePanel, title(expression),
				getTemplatePresentation().getSelectedIcon(), consolePanel);
		runnerLayoutUi.addContent(tab);
		runnerLayoutUi.selectAndFocus(tab, true, true);


		PinnedGrepsState.RunConfigurationRef runConfigurationRef = pinAction.getRunConfigurationRef();
		quickFilterPanel.setApplyCallback(new Callback() {
			@Override
			public void apply(GrepModel grepModel) {
				copyingListener.modelUpdated(grepModel);
				tab.setDisplayName(title(grepModel.getExpression()));
				PinnedGrepsState.getInstance(project).update(runConfigurationRef, parentConsoleUUID, consoleUUID, grepModel, false);
			}
		});

		originalConsoleView.flushDeferredText();
		for (String s : originalConsoleView.getEditor().getDocument().getText().split("\n")) {
			copyingListener.process(s + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
		}
		copyingFilter.addListener(copyingListener);

		if (runContentDescriptor != null) {
			Disposer.register(runContentDescriptor, tab);
		}
		Disposer.register(tab, consolePanel);
		Disposer.register(consolePanel, newConsole);
		Disposer.register(consolePanel, copyingListener);
		Disposer.register(consolePanel, quickFilterPanel);
		Disposer.register(consolePanel, new Disposable() {
			@Override
			public void dispose() {
				copyingFilter.removeListener(copyingListener);
			}
		});


		Disposable inactiveTitleDisposer;
		Container parent = originalConsoleView.getParent();
		if (parent instanceof MyJPanel && !Disposer.isDisposed((MyJPanel) parent)) {
			inactiveTitleDisposer = (MyJPanel) parent;
		} else {
			inactiveTitleDisposer = originalConsoleView;
		}

		Disposer.register(inactiveTitleDisposer, new Disposable() {
			@Override
			public void dispose() {
				// dispose chained grep consoles
				Disposer.dispose(quickFilterPanel);
				tab.setDisplayName(title(tab.getDisplayName()) + " (Inactive)");
			}
		});
		return newConsole;
	}

	@Nullable
	public String getConsoleUUID(ConsoleViewImpl originalConsoleView) {
		String parentConsoleUUID = null;
		Container parent = originalConsoleView.getParent();
		if (parent instanceof MyJPanel) {
			parentConsoleUUID = ((MyJPanel) parent).getConsoleUUID();
		}
		return parentConsoleUUID;
	}

	protected String title(String expression) {
		return StringUtils.substring(expression, 0, 20);
	}

	@NotNull
	protected String getExpression(AnActionEvent e) {
		String s = Utils.getSelectedString(e);
		if (s == null)
			s = "";
		if (s.endsWith("\n")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	@Nullable
	protected String getContentType(@NotNull RunnerLayoutUi runnerLayoutUi) {
		ContentManager contentManager = runnerLayoutUi.getContentManager();
		Content selectedContent = contentManager.getSelectedContent();
		return RunnerLayoutUiImpl.CONTENT_TYPE.get(selectedContent);
	}

	public interface Callback {

		void apply(GrepModel grepModel);
	}

	@Nullable
	private RunnerLayoutUi getRunnerLayoutUi(Project eventProject, ConsoleViewImpl originalConsoleView) {
		RunnerLayoutUi runnerLayoutUi = null;

		final RunContentDescriptor selectedContent = getRunContentDescriptor(eventProject);
		if (selectedContent != null) {
			runnerLayoutUi = selectedContent.getRunnerLayoutUi();
		}

		if (runnerLayoutUi == null) {
			XDebugSession debugSession = XDebuggerManager.getInstance(eventProject).getDebugSession(
					originalConsoleView);
			if (debugSession != null) {
				runnerLayoutUi = debugSession.getUI();
			}
			if (debugSession == null) {
				XDebugSession currentSession = XDebuggerManager.getInstance(eventProject).getCurrentSession();
				if (currentSession != null) {
					runnerLayoutUi = currentSession.getUI();
				}
			}
		}

		if (runnerLayoutUi == null) {
			Container parent = originalConsoleView.getParent();
			if (parent instanceof MyJPanel) {
				runnerLayoutUi = ((MyJPanel) parent).runnerLayoutUi;
			}
		}
		return runnerLayoutUi;
	}

	private RunContentDescriptor getRunContentDescriptor(Project eventProject) {
		RunContentManager contentManager = ExecutionManager.getInstance(eventProject).getContentManager();
		return contentManager.getSelectedContent();
	}

	public static class LightProcessHandler extends ProcessHandler {
		@Override
		protected void destroyProcessImpl() {
			throw new UnsupportedOperationException();
		}

		@Override
		protected void detachProcessImpl() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean detachIsDefault() {
			return false;
		}

		@Override
		@Nullable
		public OutputStream getProcessInput() {
			return null;
		}
	}

	private static MyJPanel createConsolePanel(RunnerLayoutUi runnerLayoutUi, ConsoleView view, ActionGroup actions,
											   GrepPanel comp, String consoleUUID) {
		MyJPanel panel = new MyJPanel(runnerLayoutUi, consoleUUID);
		panel.setLayout(new BorderLayout());
		panel.add(comp.getRootComponent(), BorderLayout.NORTH);
		panel.add(view.getComponent(), BorderLayout.CENTER);
		ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, actions,
				false);
		panel.add(actionToolbar.getComponent(), BorderLayout.WEST);
		return panel;
	}

	private ConsoleView createConsole(@NotNull Project project, @NotNull ProcessHandler processHandler) {
		ConsoleView console = ServiceManager.getInstance().createConsoleWithoutInputFilter(project);
		console.attachToProcess(processHandler);
		return console;
	}

	private ConsoleView getConsoleView(AnActionEvent e) {
		return e.getData(LangDataKeys.CONSOLE_VIEW);
	}

	@Override
	public void update(AnActionEvent e) {
		Presentation presentation = e.getPresentation();
		boolean enabled = false;

		Project eventProject = getEventProject(e);
		ConsoleViewImpl originalConsoleView = (ConsoleViewImpl) getConsoleView(e);
		GrepCopyingFilter copyingFilter = ServiceManager.getInstance().getCopyingFilter(originalConsoleView);
		if (eventProject != null && copyingFilter != null) {
			RunnerLayoutUi runnerLayoutUi = getRunnerLayoutUi(eventProject, originalConsoleView);
			enabled = runnerLayoutUi != null && getContentType(runnerLayoutUi) != null;
		}

		presentation.setEnabled(enabled);

	}

	static class MyJPanel extends JPanel implements Disposable {
		private RunnerLayoutUi runnerLayoutUi;
		private final String consoleUUID;

		public MyJPanel(RunnerLayoutUi runnerLayoutUi, String consoleUUID) {
			this.runnerLayoutUi = runnerLayoutUi;
			this.consoleUUID = consoleUUID;
		}

		public String getConsoleUUID() {
			return consoleUUID;
		}

		@Override
		public void dispose() {
			runnerLayoutUi = null;
			//TODO leak when closing tail by Close button
//			myPreferredFocusableComponent com.intellij.ui.tabs.TabInfo    

			removeAll();
		}
	}

	public static class PinAction extends ToggleAction implements DumbAware {
		private boolean pinned;
		private final GrepPanel quickFilterPanel;
		private final String parentConsoleUUID;
		private String consoleUUID;
		private Project myProject;
		private PinnedGrepsState.RunConfigurationRef runConfigurationRef;

		public PinAction(Project myProject, GrepPanel quickFilterPanel, RunContentDescriptor runContentDescriptor, String parentConsoleUUID, String consoleUUID) {
			super("Pin", "Reopen on next run", AllIcons.General.Pin_tab);
			this.quickFilterPanel = quickFilterPanel;
			this.parentConsoleUUID = parentConsoleUUID;
			this.consoleUUID = consoleUUID;
			this.myProject = myProject;
			runConfigurationRef = new PinnedGrepsState.RunConfigurationRef(runContentDescriptor.getDisplayName(), runContentDescriptor.getIcon());
			PinnedGrepsState projectComponent = PinnedGrepsState.getInstance(this.myProject);
			projectComponent.register(this);
			pinned = projectComponent.isPinned(this);
		}

		@Override
		public boolean isSelected(AnActionEvent anActionEvent) {
			return pinned;
		}

		public void refreshPinStatus(PinnedGrepsState projectComponent) {
			pinned = projectComponent.isPinned(this);
		}

		@Override
		public void setSelected(AnActionEvent anActionEvent, boolean b) {
			pinned = b;
			PinnedGrepsState projectComponent = PinnedGrepsState.getInstance(myProject);
			if (pinned) {
				projectComponent.pin(this);
			} else {
				projectComponent.unpin(this);
			}
		}

		public GrepModel getModel() {
			return quickFilterPanel.getModel();
		}

		public boolean isPinned() {
			return pinned;
		}

		public String getParentConsoleUUID() {
			return parentConsoleUUID;
		}

		public String getConsoleUUID() {
			return consoleUUID;
		}

		public Project getMyProject() {
			return myProject;
		}

		@NotNull
		public PinnedGrepsState.RunConfigurationRef getRunConfigurationRef() {
			return runConfigurationRef;
		}
	}
}

