package com.jetbrains.teamcity.command;

import java.text.MessageFormat;

import javax.naming.directory.InvalidAttributesException;

import com.jetbrains.teamcity.EAuthorizationException;
import com.jetbrains.teamcity.ECommunicationException;
import com.jetbrains.teamcity.ERemoteError;
import com.jetbrains.teamcity.Server;
import com.jetbrains.teamcity.resources.TCAccess;
import com.jetbrains.teamcity.runtime.IProgressMonitor;

public class Logout implements ICommand {

	private static final String ID = Messages.getString("Logout.command.id"); //$NON-NLS-1$
	
	private String myResultDescription;

	public void execute(Server server, Args args, final IProgressMonitor monitor) throws EAuthorizationException, ECommunicationException, ERemoteError, InvalidAttributesException {
		if(args.hasArgument(CommandRunner.HOST_ARG)){
			final String url = args.getArgument(CommandRunner.HOST_ARG);
			TCAccess.getInstance().removeCredential(url);
			myResultDescription = MessageFormat.format(Messages.getString("Logout.result.ok.pattern"), url); //$NON-NLS-1$
			return;
		}
		myResultDescription = getUsageDescription();
	}

	public String getCommandDescription() {
		return Messages.getString("Logout.help.description"); //$NON-NLS-1$
	}

	public String getId() {
		return ID;
	}

	public String getUsageDescription() {
		return MessageFormat.format(Messages.getString("Logout.help.usage.pattern"), getCommandDescription(), CommandRunner.HOST_ARG); //$NON-NLS-1$
	}

	public boolean isConnectionRequired(final Args args) {
		return false;
	}
	
	public String getResultDescription() {
		return myResultDescription;
	}

}