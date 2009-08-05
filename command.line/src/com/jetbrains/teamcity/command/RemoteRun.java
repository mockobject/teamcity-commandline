package com.jetbrains.teamcity.command;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

import javax.naming.directory.InvalidAttributesException;

import jetbrains.buildServer.AddToQueueRequest;
import jetbrains.buildServer.AddToQueueResult;
import jetbrains.buildServer.TeamServerSummaryData;
import jetbrains.buildServer.UserChangeInfoData;
import jetbrains.buildServer.UserChangeStatus;
import jetbrains.buildServer.serverSide.userChanges.PersonalChangeCommitDecision;
import jetbrains.buildServer.serverSide.userChanges.PersonalChangeDescriptor;
import jetbrains.buildServer.serverSide.userChanges.PreTestedCommitType;
import jetbrains.buildServer.vcs.patches.LowLevelPatchBuilder;
import jetbrains.buildServer.vcs.patches.LowLevelPatchBuilderImpl;
import jetbrains.buildServer.vcs.patches.PatchBuilderImpl;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpConnection;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.SimpleHttpConnectionManager;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;

import com.jetbrains.teamcity.EAuthorizationException;
import com.jetbrains.teamcity.ECommunicationException;
import com.jetbrains.teamcity.ERemoteError;
import com.jetbrains.teamcity.Server;
import com.jetbrains.teamcity.URLFactory;
import com.jetbrains.teamcity.Util;
import com.jetbrains.teamcity.resources.IShare;
import com.jetbrains.teamcity.resources.TCAccess;
import com.jetbrains.teamcity.runtime.IProgressMonitor;

public class RemoteRun implements ICommand {

	private static final int SLEEP_INTERVAL = 1000 * 10;
	private static final int DEFAULT_TIMEOUT = 1000 * 60 * 60;

	private static final String ID = Messages.getString("RemoteRun.command.id"); //$NON-NLS-1$
	
	private static final String CONFIGURATION_PARAM = Messages.getString("RemoteRun.config.runtime.param"); //$NON-NLS-1$
	private static final String CONFIGURATION_PARAM_LONG = Messages.getString("RemoteRun.config.runtime.param.long"); //$NON-NLS-1$
	
	private static final String MESSAGE_PARAM = Messages.getString("RemoteRun.message.runtime.param"); //$NON-NLS-1$
	private static final String MESSAGE_PARAM_LONG = Messages.getString("RemoteRun.message.runtime.param.long"); //$NON-NLS-1$
	
	private static final String TIMEOUT_PARAM = Messages.getString("RemoteRun.timeout.runtime.param"); //$NON-NLS-1$
	private static final String TIMEOUT_PARAM_LONG = Messages.getString("RemoteRun.timeout.runtime.param.long"); //$NON-NLS-1$
	
	private static final String NO_WAIT_SWITCH = Messages.getString("RemoteRun.nowait.runtime.param"); //$NON-NLS-1$
	private static final String NO_WAIT_SWITCH_LONG = Messages.getString("RemoteRun.nowait.runtime.param.long"); //$NON-NLS-1$
	
	private static final String PATCHES_FOLDER = "."; //$NON-NLS-1$
	
	private String myConfigurationId;
	private Collection<File> myFiles;
	private HashMap<IShare, ArrayList<File>> myRootMap;
	private Server myServer;
	private String myComments;
	private boolean isNoWait = false;
	private long myTimeout;
	private String myResultDescription;

	public void execute(final Server server, Args args, final IProgressMonitor monitor) throws EAuthorizationException, ECommunicationException, ERemoteError, InvalidAttributesException {
		if(args.hasArgument(CONFIGURATION_PARAM, CONFIGURATION_PARAM_LONG, MESSAGE_PARAM, MESSAGE_PARAM_LONG) ){
			myServer = server;
			//configuration
			myConfigurationId = args.getArgument(CONFIGURATION_PARAM, CONFIGURATION_PARAM_LONG);
			//comment
			myComments = args.getArgument(MESSAGE_PARAM, MESSAGE_PARAM_LONG);
			//wait/no wait for build result
			if(args.hasArgument(NO_WAIT_SWITCH, NO_WAIT_SWITCH_LONG)){
				isNoWait  = true;
			}
			//timeout
			if(args.hasArgument(TIMEOUT_PARAM, TIMEOUT_PARAM_LONG)){
				myTimeout = Long.valueOf(args.getArgument(TIMEOUT_PARAM, TIMEOUT_PARAM_LONG));
			} else {
				myTimeout = DEFAULT_TIMEOUT;
			}
			//lets go...
			myFiles = getFiles(args, monitor);
			myRootMap = getRootMap(myFiles, monitor);
			
			//start RR
			final long chaneListId = fireRemoteRun(myRootMap, monitor);
			//process result
			if(isNoWait){
				myResultDescription = MessageFormat.format(Messages.getString("RemoteRun.schedule.result.ok.pattern"), chaneListId); //$NON-NLS-1$
				
			} else { 
				final PersonalChangeDescriptor result = waitForSuccessResult(chaneListId, myTimeout, monitor);
				myResultDescription = MessageFormat.format(Messages.getString("RemoteRun.build.result.ok.pattern"), chaneListId, result.getPersonalChangeStatus()); //$NON-NLS-1$
				
			}
			return;
		}
		myResultDescription = getUsageDescription();
	}


	private PersonalChangeDescriptor waitForSuccessResult(final long changeListId, final long timeOut, IProgressMonitor monitor) throws ECommunicationException, ERemoteError {
		monitor.beginTask(Messages.getString("RemoteRun.wait.for.build.step.name")); //$NON-NLS-1$
		try{
			final long startTime = System.currentTimeMillis();
			UserChangeStatus prevCurrentStatus = null;
			while ((System.currentTimeMillis() - startTime) < timeOut) {
				final TeamServerSummaryData summary = myServer.getSummary();
				for(final UserChangeInfoData data : summary.getPersonalChanges()){
					if(data.getPersonalDesc() != null && data.getPersonalDesc().getId() == changeListId){
						//check builds status 
						final UserChangeStatus currentStatus = data.getChangeStatus();
						if(!currentStatus.equals(prevCurrentStatus)){
							prevCurrentStatus = currentStatus;
							monitor.worked(MessageFormat.format(Messages.getString("RemoteRun.wait.for.build.statuschanged.step.name"), currentStatus)); //$NON-NLS-1$
						}
						if (UserChangeStatus.FAILED_WITH_RESPONSIBLE == currentStatus 
								|| UserChangeStatus.FAILED == currentStatus  
								|| UserChangeStatus.CANCELED == currentStatus) {
							throw new ERemoteError(MessageFormat.format(Messages.getString("RemoteRun.build.failed.error.pattern"), currentStatus)); //$NON-NLS-1$
						}
						//check commit status
						final PersonalChangeDescriptor descriptor = data.getPersonalDesc();
						PersonalChangeCommitDecision commitStatus = descriptor.getPersonalChangeStatus();
						if(PersonalChangeCommitDecision.COMMIT == commitStatus){
							//OK
							return descriptor;
							
						} else if (PersonalChangeCommitDecision.DO_NOT_COMMIT == commitStatus){
							//build is OK, but commit is not allowed
							throw new ERemoteError(MessageFormat.format(Messages.getString("RemoteRun.build.ok.commit.rejected.error.pattern"), commitStatus)); //$NON-NLS-1$
						}
					}
				}
				try {
					Thread.sleep(SLEEP_INTERVAL);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			//so, timeout exceed
			throw new RuntimeException(MessageFormat.format(Messages.getString("RemoteRun.wait.for.build.timeout.exceed.error"), myTimeout, changeListId)); //$NON-NLS-1$
		} finally {
			monitor.done(""); //to be sure //$NON-NLS-1$
		}
	}


	private long fireRemoteRun(final HashMap<IShare, ArrayList<File>> map, final IProgressMonitor monitor) throws ECommunicationException, ERemoteError {
		final int currentUser = myServer.getCurrentUser();
		try {
			//prepare change list & patch for remote run
			final long changeId = createChangeList(myServer.getURL(), currentUser, myConfigurationId, myRootMap, monitor);
			//schedule for execution and process status
			final ArrayList<AddToQueueRequest> batch = new ArrayList<AddToQueueRequest>();
			final AddToQueueRequest request = new AddToQueueRequest(myConfigurationId, changeId);
			batch.add(request);
			monitor.beginTask(Messages.getString("RemoteRun.scheduling.build.step.name")); //$NON-NLS-1$
			final AddToQueueResult result = myServer.addRemoteRunToQueue(batch, myComments);//TODO: process Result here
			if(result.hasFailures()){
				throw new ERemoteError(result.getFailureReason(myConfigurationId));
			}
			monitor.done();
			return changeId;
		} catch (IOException e) {
			throw new ECommunicationException(e);
		}
		
	}
	
	
	private long createChangeList(String serverURL, int userId, String configuration, HashMap<IShare,ArrayList<File>> map, 
			final IProgressMonitor monitor) throws IOException, ECommunicationException {
		
		monitor.beginTask(Messages.getString("RemoteRun.preparing.patch..step.name")); //$NON-NLS-1$
		final File patch = createPatch(createPatchFile(serverURL), map);
		patch.deleteOnExit();
		monitor.done();
		
		monitor.beginTask(Messages.getString("RemoteRun.send.patch.step.name")); //$NON-NLS-1$
		final SimpleHttpConnectionManager manager = new SimpleHttpConnectionManager();
		HostConfiguration hostConfiguration = new HostConfiguration();
		hostConfiguration.setHost(new URI(serverURL, false));
		HttpConnection connection = manager.getConnection(hostConfiguration);

		if (!connection.isOpen()) {
			connection.open();
		}
		String _uri = serverURL;
		if (!serverURL.endsWith("/")) { //$NON-NLS-1$
			_uri += "/"; //$NON-NLS-1$
		}
		_uri += "uploadChanges.html"; //$NON-NLS-1$
		final PostMethod postMethod = new PostMethod(_uri);
		final BufferedInputStream content = new BufferedInputStream(new FileInputStream(patch));
		try {
			postMethod.setRequestEntity(new InputStreamRequestEntity(content, patch.length()));
			postMethod.setQueryString(new NameValuePair[] {
					new NameValuePair("userId", String.valueOf(userId)), //$NON-NLS-1$
					new NameValuePair("description", myComments), //$NON-NLS-1$
					new NameValuePair("date", String.valueOf(System.currentTimeMillis())), //$NON-NLS-1$
					new NameValuePair("commitType", String.valueOf(PreTestedCommitType.COMMIT_IF_SUCCESSFUL.getId())), });//TODO: make argument //$NON-NLS-1$
			postMethod.execute(new HttpState(), connection);
		} finally {
			content.close();
		}
		//post requests to queue
		final String response = postMethod.getResponseBodyAsString();
		monitor.done();
		
		return Long.parseLong(response);
	}


	private File createPatch(File patchFile, final HashMap<IShare, ArrayList<File>> map) throws IOException, ECommunicationException {
		DataOutputStream os = null;
		LowLevelPatchBuilderImpl patcher = null;
		try{
			os = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(patchFile)));
			patcher = new LowLevelPatchBuilderImpl(os);
			//
			for(final Entry<IShare, ArrayList<File>> entry : map.entrySet()){
				final URLFactory factory = URLFactory.getFactory(entry.getKey()); 
				for(final File file : entry.getValue()){
					LowLevelPatchBuilder.WriteFileContent content = new PatchBuilderImpl.StreamWriteFileContent(new BufferedInputStream(new FileInputStream(file)), file.length());
					final String fileUrl = factory.getUrl(file);
					if(file.exists()){
						patcher.changeBinary(fileUrl, (int) file.length(), content, false);
					} else {
						patcher.delete(fileUrl, true, false);
					}
				}
			}
			//
		} finally{
			patcher.exit(""); //$NON-NLS-1$
			if (patcher != null) {
				patcher.close();
			}
			if (os != null) {
				try {
					os.close();
				} catch (IOException e) {
				}
			}
		}
		return patchFile;

	}

	private static File createPatchFile(String url) {
		File stateLocation = new File(PATCHES_FOLDER);
		try {
			url = new String(Base64.encodeBase64(MessageDigest.getInstance("MD5").digest(url.getBytes()))); //$NON-NLS-1$
		} catch (NoSuchAlgorithmException e) {
			//
		}
		File file = new File(stateLocation, url + ".patch"); //$NON-NLS-1$
		file.getParentFile().mkdirs();
		return file;
	}

	private HashMap<IShare, ArrayList<File>> getRootMap(final Collection<File> files, IProgressMonitor monitor) throws IllegalArgumentException {
		final TCAccess access = TCAccess.getInstance();
		if(access.roots().isEmpty()){
			throw new IllegalArgumentException(Messages.getString("RemoteRun.no.shares.for.remoterun.error.message")); //$NON-NLS-1$
		}
		final HashMap<IShare, ArrayList<File>> result = new HashMap<IShare, ArrayList<File>>();
		for(final File file : files){
			final IShare root = access.getRoot(file.getAbsolutePath());
			if(root == null){
				throw new IllegalArgumentException(MessageFormat.format(Messages.getString("RemoteRun.passed.path.is.not.shared.error.message"), file.getAbsolutePath())); //$NON-NLS-1$
			}
			ArrayList<File> rootFiles = result.get(root);
			if(rootFiles == null){
				rootFiles = new ArrayList<File>();
				result.put(root, rootFiles);
			}
			rootFiles.add(file);
		}
		return result;
	}

	private Collection<File> getFiles(Args args, IProgressMonitor monitor) throws IllegalArgumentException {
		monitor.beginTask(Messages.getString("RemoteRun.collect.changes.step.name")); //$NON-NLS-1$
		final String[] elements = args.getArguments();
		int i = 0;//skip command
		while (i < elements.length) {
			final String currentToken = elements[i].toLowerCase();
			if(elements[i].startsWith("-")){ //$NON-NLS-1$
				if(elements[i].toLowerCase().equals(NO_WAIT_SWITCH) || currentToken.equals(NO_WAIT_SWITCH_LONG)){
					i++; //single token
				} else {
					i++; //arg
					i++; //args value
				}
			} else {
				//reach files
				break;
			}
		}
		
		final HashSet<File> result = new HashSet<File>();
		for (; i < elements.length; i++) {
			final String path = elements[i];
			final Collection<File> files;
			if(!path.startsWith("@")){ //$NON-NLS-1$
				files = Util.getFiles(path);
			} else {
				files = Util.getFiles(new File(path.substring(1, path.length())));
			}
			//filter out system files 
			result.addAll(Util.SVN_FILES_FILTER.accept(Util.CVS_FILES_FILTER.accept(files)));
		}
		if (result.size() == 0) {
			throw new IllegalArgumentException(Messages.getString("RemoteRun.no.files.collected.for.remoterun.eror.message")); //$NON-NLS-1$
		}
		monitor.done(MessageFormat.format(Messages.getString("RemoteRun.collect.changes.step.result.pattern"), result.size())); //$NON-NLS-1$
		return result;
	}


	public String getId() {
		return ID;
	}

	public boolean isConnectionRequired(final Args args) {
		return true;
	}

	public String getUsageDescription() {
		return MessageFormat.format(Messages.getString("RemoteRun.help.usage.pattern"),  //$NON-NLS-1$
				getCommandDescription(),
				getId(), CONFIGURATION_PARAM, CONFIGURATION_PARAM_LONG, 
				MESSAGE_PARAM, MESSAGE_PARAM_LONG, 
				TIMEOUT_PARAM, TIMEOUT_PARAM_LONG,
				NO_WAIT_SWITCH, NO_WAIT_SWITCH_LONG,
				CONFIGURATION_PARAM, CONFIGURATION_PARAM_LONG,
				MESSAGE_PARAM, MESSAGE_PARAM_LONG,
				TIMEOUT_PARAM, TIMEOUT_PARAM_LONG,
				NO_WAIT_SWITCH, NO_WAIT_SWITCH_LONG
				);
	}
	
	public String getCommandDescription() {
		return Messages.getString("RemoteRun.help.description"); //$NON-NLS-1$
	}


	public String getResultDescription() {
		return myResultDescription;
	}
	

}