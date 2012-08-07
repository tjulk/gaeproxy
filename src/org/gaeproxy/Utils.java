package org.gaeproxy;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.util.Log;

public class Utils {

	/**
	 * Internal thread used to execute scripts (as root or not).
	 */
	private static final class ScriptRunner extends Thread {
		private final File file;
		private final String script;
		private final StringBuilder res;
		private final boolean asroot;
		public int exitcode = -1;
		// private Process exec;
		private int mProcId;
		private FileDescriptor mTermFd;

		/**
		 * Creates a new script runner.
		 * 
		 * @param file
		 *            temporary script file
		 * @param script
		 *            script to run
		 * @param res
		 *            response output
		 * @param asroot
		 *            if true, executes the script as root
		 */
		public ScriptRunner(File file, String script, StringBuilder res,
				boolean asroot) {
			this.file = file;
			this.script = script;
			this.res = res;
			this.asroot = asroot;
		}

		private int createSubprocess(int[] processId, String cmd) {
			ArrayList<String> argList = parse(cmd);
			String arg0 = argList.get(0);
			String[] args = argList.toArray(new String[1]);

			mTermFd = Exec.createSubprocess(arg0, args, null, processId);
			return processId[0];
		}

		/**
		 * Destroy this script runner
		 */
		@Override
		public synchronized void destroy() {
			try {
				Exec.hangupProcessGroup(mProcId);
				Exec.close(mTermFd);
			} catch (NoClassDefFoundError ignore) {
				// Nothing
			}
		}

		private ArrayList<String> parse(String cmd) {
			final int PLAIN = 0;
			final int WHITESPACE = 1;
			final int INQUOTE = 2;
			int state = WHITESPACE;
			ArrayList<String> result = new ArrayList<String>();
			int cmdLen = cmd.length();
			StringBuilder builder = new StringBuilder();
			for (int i = 0; i < cmdLen; i++) {
				char c = cmd.charAt(i);
				if (state == PLAIN) {
					if (Character.isWhitespace(c)) {
						result.add(builder.toString());
						builder.delete(0, builder.length());
						state = WHITESPACE;
					} else if (c == '"') {
						state = INQUOTE;
					} else {
						builder.append(c);
					}
				} else if (state == WHITESPACE) {
					if (Character.isWhitespace(c)) {
						// do nothing
					} else if (c == '"') {
						state = INQUOTE;
					} else {
						state = PLAIN;
						builder.append(c);
					}
				} else if (state == INQUOTE) {
					if (c == '\\') {
						if (i + 1 < cmdLen) {
							i += 1;
							builder.append(cmd.charAt(i));
						}
					} else if (c == '"') {
						state = PLAIN;
					} else {
						builder.append(c);
					}
				}
			}
			if (builder.length() > 0) {
				result.add(builder.toString());
			}
			return result;
		}

		@Override
		public void run() {
			try {
				new File(DEFOUT_FILE).createNewFile();
				file.createNewFile();
				final String abspath = file.getAbsolutePath();

				// TODO: Rewrite this line
				// make sure we have execution permission on the script file
				// Runtime.getRuntime().exec("chmod 755 " + abspath).waitFor();

				// Write the script to be executed
				final OutputStreamWriter out = new OutputStreamWriter(
						new FileOutputStream(file));
				out.write("#!/system/bin/sh\n");
				out.write(script);
				if (!script.endsWith("\n"))
					out.write("\n");
				out.write("exit\n");
				out.flush();
				out.close();

				if (this.asroot) {
					// Create the "su" request to run the script
					// exec = Runtime.getRuntime().exec(
					// root_shell + " -c " + abspath);

					int pid[] = new int[1];
					mProcId = createSubprocess(pid, root_shell + " -c "
							+ abspath);
				} else {
					// Create the "sh" request to run the script
					// exec = Runtime.getRuntime().exec(getShell() + " " +
					// abspath);

					int pid[] = new int[1];
					mProcId = createSubprocess(pid, getShell() + " " + abspath);
				}

				final InputStream stdout = new FileInputStream(DEFOUT_FILE);
				final byte buf[] = new byte[8192];
				int read = 0;

				exitcode = Exec.waitFor(mProcId);

				// Read stdout
				while (stdout.available() > 0) {
					read = stdout.read(buf);
					if (res != null)
						res.append(new String(buf, 0, read));
				}

			} catch (Exception ex) {
				if (res != null)
					res.append("\n" + ex);
			} finally {
				destroy();
			}
		}
	}

	public final static String TAG = "GAEProxy";

	public final static String DEFAULT_SHELL = "/system/bin/sh";
	public final static String DEFAULT_ROOT = "/system/bin/su";

	public final static String ALTERNATIVE_ROOT = "/system/xbin/su";
	public final static String DEFAULT_IPTABLES = "/data/data/org.gaeproxy/iptables";
	public final static String ALTERNATIVE_IPTABLES = "/system/bin/iptables";
	public final static String SCRIPT_FILE = "/data/data/org.gaeproxy/script";
	public final static String DEFOUT_FILE = "/data/data/org.gaeproxy/defout";

	public final static int TIME_OUT = -99;
	private static boolean initialized = false;
	private static int hasRedirectSupport = -1;

	private static int isRoot = -1;
	private static String shell = null;
	private static String root_shell = null;
	private static String iptables = null;

	private static String data_path = null;

	private static void checkIptables() {

		if (!isRoot()) {
			iptables = DEFAULT_IPTABLES;
			return;
		}

		// Check iptables binary
		iptables = DEFAULT_IPTABLES;

		String lines = null;

		boolean compatible = false;
		boolean version = false;

		StringBuilder sb = new StringBuilder();
		String command = iptables + " --version\n" + iptables
				+ " -L -t nat -n\n" + "exit\n";

		int exitcode = runScript(command, sb, 10 * 1000, true);

		if (exitcode == TIME_OUT)
			return;

		lines = sb.toString();

		if (lines.contains("OUTPUT")) {
			compatible = true;
		}
		if (lines.contains("v1.4.")) {
			version = true;
		}

		if (!compatible || !version) {
			iptables = ALTERNATIVE_IPTABLES;
			if (!new File(iptables).exists())
				iptables = "iptables";
		}

	}

	public static Drawable getAppIcon(Context c, int uid) {
		PackageManager pm = c.getPackageManager();
		Drawable appIcon = c.getResources().getDrawable(
				R.drawable.sym_def_app_icon);
		String[] packages = pm.getPackagesForUid(uid);

		if (packages != null) {
			if (packages.length == 1) {
				try {
					ApplicationInfo appInfo = pm.getApplicationInfo(
							packages[0], 0);
					appIcon = pm.getApplicationIcon(appInfo);
				} catch (NameNotFoundException e) {
					Log.e(c.getPackageName(),
							"No package found matching with the uid " + uid);
				}
			}
		} else {
			Log.e(c.getPackageName(), "Package not found for uid " + uid);
		}

		return appIcon;
	}

	public static String getDataPath(Context ctx) {

		if (data_path == null) {

			if (Environment.MEDIA_MOUNTED.equals(Environment
					.getExternalStorageState())) {
				data_path = Environment.getExternalStorageDirectory()
						.getAbsolutePath();
			} else {
				data_path = ctx.getFilesDir().getAbsolutePath();
			}

			Log.d(TAG, "Python Data Path: " + data_path);
		}

		return data_path;
	}

	public static boolean getHasRedirectSupport() {
		if (hasRedirectSupport == -1)
			initHasRedirectSupported();
		return hasRedirectSupport == 1 ? true : false;
	}

	public static String getIptables() {
		if (iptables == null)
			checkIptables();
		return iptables;
	}

	private static String getShell() {
		if (shell == null) {
			shell = DEFAULT_SHELL;
			if (!new File(shell).exists())
				shell = "sh";
		}
		return shell;
	}

	public static String getSignature(Context ctx) {
		Signature sig = null;
		try {
			Signature[] sigs;
			sigs = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
					PackageManager.GET_SIGNATURES).signatures;
			if (sigs != null && sigs.length > 0)
				sig = sigs[0];
		} catch (Exception ignore) {
			// Nothing
		}
		if (sig == null)
			return null;
		return sig.toCharsString().substring(11, 256);
	}

	public static void initHasRedirectSupported() {

		if (!Utils.isRoot())
			return;

		StringBuilder sb = new StringBuilder();
		String command = Utils.getIptables()
				+ " -t nat -A OUTPUT -p udp --dport 54 -j REDIRECT --to 8154";

		int exitcode = runScript(command, sb, 10 * 1000, true);

		String lines = sb.toString();

		hasRedirectSupport = 1;

		// flush the check command
		Utils.runRootCommand(command.replace("-A", "-D"));

		if (exitcode == TIME_OUT)
			return;

		if (lines.contains("No chain/target/match")) {
			hasRedirectSupport = 0;
		}
	}

	public static boolean isInitialized() {
		if (initialized)
			return true;
		else {
			initialized = true;
			return false;
		}

	}

	public static boolean isRoot() {

		if (isRoot != -1)
			return isRoot == 1 ? true : false;

		// switch between binaries
		if (new File(DEFAULT_ROOT).exists()) {
			root_shell = DEFAULT_ROOT;
		} else if (new File(ALTERNATIVE_ROOT).exists()) {
			root_shell = ALTERNATIVE_ROOT;
		} else {
			root_shell = "su";
		}

		String lines = null;

		StringBuilder sb = new StringBuilder();
		String command = "ls /\n" + "exit\n";

		int exitcode = runScript(command, sb, 10 * 1000, true);

		if (exitcode == TIME_OUT) {
			return false;
		}

		lines = sb.toString();

		if (lines.contains("system")) {
			isRoot = 1;
		}

		return isRoot == 1 ? true : false;
	}

	public static boolean runCommand(String command) {

		return runCommand(command, 10 * 1000);

	}

	public static boolean runCommand(String command, int timeout) {

		Log.d(TAG, command);

		runScript(command, null, timeout, false);

		return true;
	}

	public static boolean runRootCommand(String command) {

		return runRootCommand(command, 10 * 1000);

	}

	public static boolean runRootCommand(String command, int timeout) {

		if (!isRoot())
			return false;

		Log.d(TAG, command);

		runScript(command, null, timeout, true);

		return true;
	}

	private synchronized static int runScript(String script, StringBuilder res,
			long timeout, boolean asroot) {
		final File file = new File(SCRIPT_FILE);
		final ScriptRunner runner = new ScriptRunner(file, script, res, asroot);
		runner.start();
		try {
			if (timeout > 0) {
				runner.join(timeout);
			} else {
				runner.join();
			}
			if (runner.isAlive()) {
				// Timed-out
				runner.destroy();
				runner.join(1000);
				return TIME_OUT;
			}
		} catch (InterruptedException ex) {
			return TIME_OUT;
		}
		return runner.exitcode;
	}
}
