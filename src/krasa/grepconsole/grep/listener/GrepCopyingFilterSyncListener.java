package krasa.grepconsole.grep.listener;

import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import krasa.grepconsole.grep.GrepModel;
import krasa.grepconsole.grep.OpenGrepConsoleAction;
import krasa.grepconsole.model.Profile;
import krasa.grepconsole.utils.Notifier;
import krasa.grepconsole.utils.Utils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class GrepCopyingFilterSyncListener implements GrepCopyingFilterListener {
	private static final Logger log = Logger.getInstance(GrepCopyingFilterSyncListener.class);

	private final OpenGrepConsoleAction.LightProcessHandler myProcessHandler;
	private final Project project;
	private volatile GrepModel.Matcher matcher;
	private volatile Profile profile;
	private volatile boolean showLimitNotification = true;
	private ThreadLocal<String> previousIncompleteToken = new ThreadLocal<>();
	private ThreadLocal<Long> previousTimestamp = new ThreadLocal<>();

	public GrepCopyingFilterSyncListener(OpenGrepConsoleAction.LightProcessHandler myProcessHandler, Project project, Profile profile) {
		this.myProcessHandler = myProcessHandler;
		this.project = project;
		this.profile = profile;
	}

	@Override
	public void modelUpdated(@NotNull GrepModel grepModel) {
		matcher = grepModel.matcher();
	}

	@Override
	public void profileUpdated(@NotNull Profile profile) {
		this.profile = profile;
	}

	@Override
	public void process(String text, ConsoleViewContentType type) {
		if (matcher == null || StringUtils.isEmpty(text)) {
			return;
		}
		List<String> split = StringUtil.split(text, "\n", false, false);
		for (int i = 0; i < split.size(); i++) {
			String s = split.get(i);

			if (!s.endsWith("\n")) {
				Long lastTimestamp = previousTimestamp.get();
				if (lastTimestamp == null || System.currentTimeMillis() - lastTimestamp < 1000) {
					if (previousIncompleteToken.get() != null) {
						previousIncompleteToken.set(previousIncompleteToken.get() + s);
						previousTimestamp.set(System.currentTimeMillis());
					} else {
						previousIncompleteToken.set(s);
						previousTimestamp.set(System.currentTimeMillis());
					}
					continue;
				}
			}

			if (previousIncompleteToken.get() != null) {
				s = previousIncompleteToken.get() + s;
				previousIncompleteToken.set(null);
				previousTimestamp.set(null);
			}

			Key stdout = ProcessOutputTypes.STDOUT;
			if (type == ConsoleViewContentType.ERROR_OUTPUT) {
				stdout = ProcessOutputTypes.STDERR;
			} else if (type == ConsoleViewContentType.SYSTEM_OUTPUT) {
				stdout = ProcessOutputTypes.SYSTEM;
			}

			String substring = profile.limitInputGrepLength_andCutNewLine(s);
			CharSequence charSequence = profile.limitProcessingTime(substring);
			try {
				if (matcher.matches(charSequence)) {
					if (!s.endsWith("\n")) {
						s = s + "\n";
					}
					myProcessHandler.notifyTextAvailable(s, stdout);
				}
			} catch (ProcessCanceledException e) {
				String message = "Grep to a subconsole took too long, aborting to prevent input freezing.\n"
						+ "Consider changing following settings: 'Match only first N characters on each line' or 'Max processing time for a line'\n"
						+ "Matcher: " + matcher + "\n" + "Line: " + Utils.toNiceLineForLog(substring);
				if (showLimitNotification) {
					showLimitNotification = false;
					Notifier.notify_GrepCopyingFilter(project, message);
				} else {
					log.warn(message);
				}
			}

		}

	}

	@Override
	public void clearStats() {

	}

	@Override
	public void dispose() {
		previousIncompleteToken.set(null);
	}
}
