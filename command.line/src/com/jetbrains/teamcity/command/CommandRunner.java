package com.jetbrains.teamcity.command;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;

import jetbrains.buildServer.IncompatiblePluginError;
import jetbrains.buildServer.serverSide.crypt.EncryptUtil;

import com.jetbrains.teamcity.Debug;
import com.jetbrains.teamcity.EAuthorizationException;
import com.jetbrains.teamcity.ECommunicationException;
import com.jetbrains.teamcity.ERemoteError;
import com.jetbrains.teamcity.Server;
import com.jetbrains.teamcity.Util;
import com.jetbrains.teamcity.resources.ICredential;
import com.jetbrains.teamcity.resources.TCAccess;
import com.jetbrains.teamcity.runtime.ConsoleProgressMonitor;
import com.jetbrains.teamcity.runtime.IProgressMonitor;

public class CommandRunner {
	
//	private static Logger LOGGER = Logger.getLogger(CommandRunner.class) ;
	
	static final String USER_ARG = Messages.getString("CommandRunner.global.runtime.param.user"); //$NON-NLS-1$
	static final String PASSWORD_ARG = Messages.getString("CommandRunner.global.runtime.param.password"); //$NON-NLS-1$
	static final String HOST_ARG = Messages.getString("CommandRunner.global.runtime.param.host"); //$NON-NLS-1$
	
	private static Comparator<ICredential> ourCredentialComaparator = new Comparator<ICredential>() {
		public int compare(ICredential o1, ICredential o2) {
			if (o1.getCreationTimestamp() > o2.getCreationTimestamp()) {
				return -1;
			} else if (o1.getCreationTimestamp() < o2.getCreationTimestamp()) {
				return 1;
			}
			return 0;
		}
	};
	
	static {
		Args.registerArgument(USER_ARG, String.format(".*%s\\s+[^\\s].*", USER_ARG)); //$NON-NLS-1$
		Args.registerArgument(PASSWORD_ARG, String.format(".*%s\\s+[^\\s].*", PASSWORD_ARG)); //$NON-NLS-1$
		Args.registerArgument(HOST_ARG, String.format(".*%s\\s+https*://.+", HOST_ARG)); //$NON-NLS-1$
	}
	
	void run(final String[] args) throws Exception{
		final Args arguments = new Args(args);
		/**
		 * instantiate Debug and set mode according to command line 
		 */
		final Debug debug = Debug.getInstance();
		debug.setDebug(arguments.isDebugOn());
		
		final IProgressMonitor monitor = new ConsoleProgressMonitor(System.out);
		
		final ICommand command = CommandRegistry.getInstance().getCommand(arguments.getCommandId());
		if(command != null){
			Server server = null;
			try{
				command.validate(arguments);
				if(command.isConnectionRequired(arguments)){
					server = openConnection(arguments, monitor);
					command.execute(server, arguments, monitor);
				} else {
					command.execute(null, arguments, monitor);
				}
				//print success result
				reportResult(command, monitor);
			} catch (Throwable e){
				//print error result
				monitor.done(String.format(Messages.getString("CommandRunner.monitor.error.found"), command.getId())); //$NON-NLS-1$
				reportError(server, command, e, monitor);
				System.exit(-1);
			}
		} else {
			final ICommand helpCommand = CommandRegistry.getInstance().getCommand(Help.ID);
			helpCommand.execute(null, arguments, monitor);
			reportResult(helpCommand, monitor);
		}
	}

	public static void main(final String[] args) throws Exception {
		new CommandRunner().run(args);
		System.exit(0);
	}
	
	private static void reportError(final Server server, final ICommand command, final Throwable e, final IProgressMonitor monitor) {
		Debug.getInstance().error(CommandRunner.class, e.getMessage(), e);
		final String rootMessage = Util.getRootCause(e).getMessage();
		if(e instanceof UnknownHostException){
			System.err.println(MessageFormat.format(Messages.getString("CommandRunner.unknown.host.error.pattern"), rootMessage)); //$NON-NLS-1$
			
		} else if (e instanceof URISyntaxException){
			System.err.println(MessageFormat.format(Messages.getString("CommandRunner.invalid.url.error.pattern"), rootMessage)); //$NON-NLS-1$
			
		} else if (e instanceof MalformedURLException){
			System.err.println(MessageFormat.format(Messages.getString("CommandRunner.invalid.url.error.pattern"), rootMessage)); //$NON-NLS-1$
				
		} else if (e instanceof EAuthorizationException){
			System.err.println(MessageFormat.format(Messages.getString("CommandRunner.invalid.credential.error.pattern"), rootMessage)); //$NON-NLS-1$
			
		} else if (e instanceof ECommunicationException){
			System.err.println(MessageFormat.format(Messages.getString("CommandRunner.could.not.connect.error.pattern"), rootMessage)); //$NON-NLS-1$
			
		} else if (e instanceof ERemoteError){
			System.err.println(MessageFormat.format(Messages.getString("CommandRunner.businesslogic.error.pattern"), rootMessage)); //$NON-NLS-1$
			
		} else if (e instanceof IncompatiblePluginError) {
			System.err.println(rootMessage); //$NON-NLS-1$
			
		} else if (e instanceof IllegalArgumentException){
			System.err.println(MessageFormat.format(Messages.getString("CommandRunner.invalid.command.arguments.error.pattern"), rootMessage)); //$NON-NLS-1$
			System.err.println();
			System.err.println(command.getUsageDescription());
			
		} else {
			e.printStackTrace();
		}
	}

	private static void reportResult(ICommand command, IProgressMonitor monitor) {
		if (command.getResultDescription() != null && command.getResultDescription().trim().length() != 0) {
			System.out.println(command.getResultDescription());
		}
	}

	static Server openConnection(final Args args, final IProgressMonitor monitor) throws MalformedURLException, ECommunicationException, EAuthorizationException {
		final String host = getHost(args);
		if(host != null){
			String user;
			String password;
			if(args.hasArgument(USER_ARG, PASSWORD_ARG)){
				user = args.getArgument(USER_ARG);
				password = args.getArgument(PASSWORD_ARG);
				
			} else {
				//try to load from saved
				final ICredential credential = TCAccess.getInstance().findCredential(host);
				if(credential != null){
					user = credential.getUser();
					try{
						password = EncryptUtil.unscramble(credential.getPassword());
					} catch (Throwable t){
						//the EncryptUtil raises exception if decoding string was not scrambled
						password = credential.getPassword();
					}
				} else {
					throw new IllegalArgumentException(MessageFormat.format(Messages.getString("CommandRunner.not.logged.in.error.pattern"), host)); //$NON-NLS-1$
				}
			}
			final Server server = new Server(new URL(host));
			monitor.beginTask(MessageFormat.format(Messages.getString("CommandRunner.connecting.step.name"), host)); //$NON-NLS-1$
			server.connect();
			monitor.done();
			//check protocol compatibility
			monitor.beginTask("Checking compatibility");
			final String remote = server.getRemoteProtocolVersion();
			final String local = server.getLocalProtocolVersion();
			Debug.getInstance().debug(CommandRunner.class, String.format("Checking protocol compatibility. Found local=%s, remote=%s", local, remote));
			if(!remote.equals(local)){
				throw new IncompatiblePluginError(String.format(Messages.getString("CommandRunner.incompatible.plugin.error.message.pattern"), remote, local));
			}
			monitor.done();
			monitor.beginTask(Messages.getString("CommandRunner.logging.step.name")); //$NON-NLS-1$
			server.logon(user, password);
			monitor.done();
			return server;
		} else {
			throw new IllegalArgumentException(MessageFormat.format(Messages.getString("CommandRunner.no.default.host.error.pattern"), HOST_ARG)); //$NON-NLS-1$
		}
	}
	
	static String getHost(final Args args){
		//load default(any) if omitted
		if(!args.hasArgument(HOST_ARG)){
			return getDefaultHost();
		} else {
			return args.getArgument(HOST_ARG);
		}
	}

	static String getDefaultHost() {
		final Collection<ICredential> credentials = TCAccess.getInstance().credentials();
		if(!credentials.isEmpty()){
			//sort by creation TS. the newest will be used as default 
			final ArrayList<ICredential> ordered = new ArrayList<ICredential>(credentials);
			Collections.sort(ordered, ourCredentialComaparator);
			final ICredential defaultCredential = ordered.iterator().next();
			Debug.getInstance().debug(CommandRunner.class, MessageFormat.format("Using \"{0}\" as Default TeamCity Server", defaultCredential.getServer())); //$NON-NLS-1$
			return defaultCredential.getServer();
		}
		Debug.getInstance().debug(CommandRunner.class, "No Default TeamCity Server found"); //$NON-NLS-1$
		return null;
	}
	
}
