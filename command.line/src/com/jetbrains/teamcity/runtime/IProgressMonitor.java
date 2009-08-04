package com.jetbrains.teamcity.runtime;

public interface IProgressMonitor {
	
	public IProgressMonitor beginTask(String name, int totalWork);
	
	public IProgressMonitor worked(String step);
	
	public IProgressMonitor done(final String message);
	
	public IProgressMonitor done();

}
