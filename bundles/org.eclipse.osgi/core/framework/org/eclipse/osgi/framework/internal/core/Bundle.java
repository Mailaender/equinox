/*******************************************************************************
 * Copyright (c) 2003,2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.framework.internal.core;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.*;
import java.util.*;
import org.eclipse.osgi.framework.adaptor.BundleData;
import org.eclipse.osgi.framework.adaptor.BundleOperation;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.service.resolver.*;
import org.osgi.framework.*;
/**
 * This object is given out to bundles and wraps the internal Bundle object. It
 * is destroyed when a bundle is uninstalled and reused if a bundle is updated.
 * This class is abstract and is extended by BundleHost and BundleFragment.
 */
public abstract class Bundle implements org.osgi.framework.Bundle, Comparable, KeyedElement {
	/** The Framework this bundle is part of */
	protected Framework framework;
	/** The state of the bundle. */
	protected volatile int state;
	/** A flag to denote whether a bundle state change is in progress */
	protected volatile Thread stateChanging;
	/** Bundle's BundleData object */
	protected BundleData bundledata;
	/** Internal object used for state change synchronization */
	protected Object statechangeLock = new Object();
	/** ProtectionDomain for the bundle */
	protected ProtectionDomain domain;

	/**
	 * This String captures the dependencies that could not be resolved
	 * as a result of not having the proper permissions.
	 * This information is collected by checkPermissions, but an exception
	 * cannot be thrown during the resolve phase. It is saved here to be thrown
	 * later (by Bundle.start for example).
	 */
	protected String permissionMsg;
	protected ManifestLocalization manifestLocalization = null;
	/**
	 * Bundle object constructor. This constructor should not perform any real
	 * work.
	 * 
	 * @param bundledata
	 *            BundleData for this bundle
	 * @param framework
	 *            Framework this bundle is running in
	 */
	protected static Bundle createBundle(BundleData bundledata, Framework framework) throws BundleException {
		if (bundledata.isFragment())
			return new BundleFragment(bundledata, framework);
		else
			return new BundleHost(bundledata, framework);
	}
	/**
	 * Bundle object constructor. This constructor should not perform any real
	 * work.
	 * 
	 * @param bundledata
	 *            BundleData for this bundle
	 * @param framework
	 *            Framework this bundle is running in
	 */
	protected Bundle(BundleData bundledata, Framework framework) throws BundleException {
		state = INSTALLED;
		stateChanging = null;
		this.bundledata = bundledata;
		this.framework = framework;
		bundledata.setBundle(this);
	}
	/**
	 * Load the bundle.
	 * 
	 * @exception org.osgi.framework.BundleException
	 */
	protected abstract void load() throws BundleException;
	/**
	 * Reload from a new bundle. This method must be called while holding the
	 * bundles lock.
	 * 
	 * @param newBundle
	 *            Dummy Bundle which contains new data.
	 * @return true if an exported package is "in use". i.e. it has been
	 *         imported by a bundle
	 * @exception org.osgi.framework.BundleException
	 */
	protected abstract boolean reload(Bundle newBundle) throws BundleException;
	/**
	 * Refresh the bundle. This is called by Framework.refreshPackages. This
	 * method must be called while holding the bundles lock.
	 * this.loader.unimportPackages must have already been called before
	 * calling this method!
	 * 
	 * @exception org.osgi.framework.BundleException
	 *                if an exported package is "in use". i.e. it has been
	 *                imported by a bundle
	 */
	protected abstract void refresh() throws BundleException;
	/**
	 * Unload the bundle. This method must be called while holding the bundles
	 * lock.
	 * 
	 * @return true if an exported package is "in use". i.e. it has been
	 *         imported by a bundle
	 */
	protected abstract boolean unload();
	/**
	 * Close the the Bundle's file.
	 *  
	 */
	protected void close() {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			if ((state & (INSTALLED)) == 0) {
				Debug.println("Bundle.close called when state != INSTALLED: " + this);
				Debug.printStackTrace(new Exception("Stack trace"));
			}
		}
		state = UNINSTALLED;
	}
	/** 
	 * Load and instantiate bundle's BundleActivator class
	 */
	protected BundleActivator loadBundleActivator() throws BundleException {
		/* load Bundle's BundleActivator if it has one */
		String activatorClassName = bundledata.getActivator();
		if (activatorClassName != null) {
			try {
				Class activatorClass = loadClass(activatorClassName, false);
				/* Create the activator for the bundle */
				return (BundleActivator) (activatorClass.newInstance());
			} catch (Throwable t) {
				if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
					Debug.printStackTrace(t);
				}
				throw new BundleException(Msg.formatter.getString("BUNDLE_INVALID_ACTIVATOR_EXCEPTION", activatorClassName, bundledata.getSymbolicName()), t);
			}
		}
		return (null);
	}
	/**
	 * This method loads a class from the bundle.
	 * 
	 * @param name
	 *            the name of the desired Class.
	 * @param checkPermission
	 *            indicates whether a permission check should be done.
	 * @return the resulting Class
	 * @exception java.lang.ClassNotFoundException
	 *                if the class definition was not found.
	 */
	protected abstract Class loadClass(String name, boolean checkPermission) throws ClassNotFoundException;
	/**
	 * Find the specified resource in this bundle.
	 * 
	 * This bundle's class loader is called to search for the named resource.
	 * If this bundle's state is <tt>INSTALLED</tt>, then only this bundle
	 * will be searched for the specified resource. Imported packages cannot be
	 * searched when a bundle has not been resolved.
	 * 
	 * @param name
	 *            The name of the resource. See <tt>java.lang.ClassLoader.getResource</tt>
	 *            for a description of the format of a resource name.
	 * @return a URL to the named resource, or <tt>null</tt> if the resource
	 *         could not be found or if the caller does not have the <tt>AdminPermission</tt>,
	 *         and the Java Runtime Environment supports permissions.
	 * 
	 * @exception java.lang.IllegalStateException
	 *                If this bundle has been uninstalled.
	 */
	public abstract URL getResource(String name);
	/**
	 * Returns the current state of the bundle.
	 * 
	 * A bundle can only be in one state at any time.
	 * 
	 * @return bundle's state.
	 */
	public int getState() {
		return (state);
	}
	/**
	 * Return true if the bundle is starting or active.
	 *  
	 */
	protected boolean isActive() {
		return ((state & (ACTIVE | STARTING)) != 0);
	}
	/**
	 * Return true if the bundle is resolved.
	 *  
	 */
	protected boolean isResolved() {
		return (state & (INSTALLED | UNINSTALLED)) == 0;
	}
	/**
	 * Start this bundle.
	 * 
	 * If the current start level is less than this bundle's start level, then
	 * the Framework must persistently mark this bundle as started and delay
	 * the starting of this bundle until the Framework's current start level
	 * becomes equal or more than the bundle's start level.
	 * <p>
	 * Otherwise, the following steps are required to start a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #ACTIVE}or {@link #STARTING}then this
	 * method returns immediately.
	 * <li>If the bundle is {@link #STOPPING}then this method may wait for
	 * the bundle to return to the {@link #RESOLVED}state before continuing.
	 * If this does not occur in a reasonable time, a {@link BundleException}
	 * is thrown to indicate the bundle was unable to be started.
	 * <li>If the bundle is not {@link #RESOLVED}, an attempt is made to
	 * resolve the bundle. If the bundle cannot be resolved, a
	 * {@link BundleException}is thrown.
	 * <li>The state of the bundle is set to {@link #STARTING}.
	 * <li>The {@link BundleActivator#start(BundleContext) start}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}is invalid or throws an exception, the state of
	 * the bundle is set back to {@link #RESOLVED}, the bundle's listeners, if
	 * any, are removed, service's registered by the bundle, if any, are
	 * unregistered, and service's used by the bundle, if any, are released. A
	 * {@link BundleException}is then thrown.
	 * <li>It is recorded that this bundle has been started, so that when the
	 * framework is restarted, this bundle will be automatically started.
	 * <li>The state of the bundle is set to {@link #ACTIVE}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STARTED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #INSTALLED},{@link #RESOLVED}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * <li>{@link BundleActivator#start(BundleContext) BundleActivator.start}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #STARTING},{@link #ACTIVE}}.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the bundle couldn't be started. This could be because
	 *                a code dependency could not be resolved or the specified
	 *                BundleActivator could not be loaded or threw an
	 *                exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled or the bundle tries to
	 *                change its own state.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public void start() throws BundleException {
		long start = 0;
		if (Debug.DEBUG && Debug.DEBUG_BUNDLE_TIME) {
			start = System.currentTimeMillis();
			System.out.println("Starting " + getSymbolicName());
		}
		framework.checkAdminPermission();
		checkValid();
		beginStateChange();
		try {
			startWorker(true);
		} finally {
			completeStateChange();
		}
		if (Debug.DEBUG && Debug.DEBUG_BUNDLE_TIME)
			System.out.println("End starting " + getSymbolicName() + " " + (System.currentTimeMillis() - start));
	}
	/**
	 * Internal worker to start a bundle.
	 * 
	 * @param persistent
	 *            if true persistently record the bundle was started.
	 */
	protected abstract void startWorker(boolean persistent) throws BundleException;
	/**
	 * Start this bundle w/o marking is persistently started.
	 * 
	 * <p>
	 * The following steps are followed to start a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #ACTIVE}or {@link #STARTING}then this
	 * method returns immediately.
	 * <li>If the bundle is {@link #STOPPING}then this method may wait for
	 * the bundle to return to the {@link #RESOLVED}state before continuing.
	 * If this does not occur in a reasonable time, a {@link BundleException}
	 * is thrown to indicate the bundle was unable to be started.
	 * <li>If the bundle is not {@link #RESOLVED}, an attempt is made to
	 * resolve the bundle. If the bundle cannot be resolved, a
	 * {@link BundleException}is thrown.
	 * <li>The state of the bundle is set to {@link #STARTING}.
	 * <li>The {@link BundleActivator#start(BundleContext) start}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}is invalid or throws an exception, the state of
	 * the bundle is set back to {@link #RESOLVED}, the bundle's listeners, if
	 * any, are removed, service's registered by the bundle, if any, are
	 * unregistered, and service's used by the bundle, if any, are released. A
	 * {@link BundleException}is then thrown.
	 * <li>The state of the bundle is set to {@link #ACTIVE}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STARTED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #INSTALLED},{@link #RESOLVED}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * <li>{@link BundleActivator#start(BundleContext) BundleActivator.start}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #STARTING},{@link #ACTIVE}}.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the bundle couldn't be started. This could be because
	 *                a code dependency could not be resolved or the specified
	 *                BundleActivator could not be loaded or threw an
	 *                exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle tries to change its own state.
	 */
	protected void resume() throws BundleException {
		if (state == UNINSTALLED) {
			return;
		}
		beginStateChange();
		try {
			startWorker(false);
		} finally {
			completeStateChange();
		}
	}
	/**
	 * Stop this bundle.
	 * 
	 * Any services registered by this bundle will be unregistered. Any
	 * services used by this bundle will be released. Any listeners registered
	 * by this bundle will be removed.
	 * 
	 * <p>
	 * The following steps are followed to stop a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #STOPPING},{@link #RESOLVED}, or
	 * {@link #INSTALLED}then this method returns immediately.
	 * <li>If the bundle is {@link #STARTING}then this method may wait for
	 * the bundle to reach the {@link #ACTIVE}state before continuing. If this
	 * does not occur in a reasonable time, a {@link BundleException}is thrown
	 * to indicate the bundle was unable to be stopped.
	 * <li>The state of the bundle is set to {@link #STOPPING}.
	 * <li>It is recorded that this bundle has been stopped, so that when the
	 * framework is restarted, this bundle will not be automatically started.
	 * <li>The {@link BundleActivator#stop(BundleContext) stop}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}throws an exception, this method will continue
	 * to stop the bundle. A {@link BundleException}will be thrown after
	 * completion of the remaining steps.
	 * <li>The bundle's listeners, if any, are removed, service's registered
	 * by the bundle, if any, are unregistered, and service's used by the
	 * bundle, if any, are released.
	 * <li>The state of the bundle is set to {@link #RESOLVED}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STOPPED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #ACTIVE},{@link #STOPPING}}.
	 * <li>{@link BundleActivator#stop(BundleContext) BundleActivator.stop}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>None.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the bundle's BundleActivator could not be loaded or
	 *                threw an exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled or the bundle tries to
	 *                change its own state.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public void stop() throws BundleException {
		framework.checkAdminPermission();
		checkValid();
		beginStateChange();
		try {
			stopWorker(true);
		} finally {
			completeStateChange();
		}
	}
	/**
	 * Internal worker to stop a bundle.
	 * 
	 * @param persistent
	 *            if true persistently record the bundle was stopped.
	 */
	protected abstract void stopWorker(boolean persistent) throws BundleException;
	/**
	 * Set the persistent status bit for the bundle.
	 * 
	 * @param mask
	 *            Mask for bit to set/clear
	 * @param state
	 *            true to set bit, false to clear bit
	 */
	protected void setStatus(final int mask, final boolean state) {
		try {
			AccessController.doPrivileged(new PrivilegedExceptionAction() {
				public Object run() throws BundleException, IOException {
					int status = bundledata.getStatus();
					boolean test = ((status & mask) != 0);
					if (test != state) {
						bundledata.setStatus(state ? (status | mask) : (status & ~mask));
						bundledata.save();
					}
					return null;
				}
			});
		} catch (PrivilegedActionException pae) {
			framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, pae.getException());
		}
	}
	/**
	 * Stop this bundle w/o marking is persistently stopped.
	 * 
	 * Any services registered by this bundle will be unregistered. Any
	 * services used by this bundle will be released. Any listeners registered
	 * by this bundle will be removed.
	 * 
	 * <p>
	 * The following steps are followed to stop a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #STOPPING},{@link #RESOLVED}, or
	 * {@link #INSTALLED}then this method returns immediately.
	 * <li>If the bundle is {@link #STARTING}then this method may wait for
	 * the bundle to reach the {@link #ACTIVE}state before continuing. If this
	 * does not occur in a reasonable time, a {@link BundleException}is thrown
	 * to indicate the bundle was unable to be stopped.
	 * <li>The state of the bundle is set to {@link #STOPPING}.
	 * <li>The {@link BundleActivator#stop(BundleContext) stop}method of the bundle's
	 * {@link BundleActivator}, if one is specified, is called. If the
	 * {@link BundleActivator}throws an exception, this method will continue
	 * to stop the bundle. A {@link BundleException}will be thrown after
	 * completion of the remaining steps.
	 * <li>The bundle's listeners, if any, are removed, service's registered
	 * by the bundle, if any, are unregistered, and service's used by the
	 * bundle, if any, are released.
	 * <li>The state of the bundle is set to {@link #RESOLVED}.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#STOPPED}is
	 * broadcast.
	 * </ol>
	 * 
	 * <h5>Preconditons</h5>
	 * <ul>
	 * <li>getState() in {{@link #ACTIVE}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #ACTIVE},{@link #STOPPING}}.
	 * <li>{@link BundleActivator#stop(BundleContext) BundleActivator.stop}has been called
	 * and did not throw an exception.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>None.
	 * </ul>
	 * 
	 * @param lock
	 *            true if state change lock should be held when returning from
	 *            this method.
	 * @exception BundleException
	 *                If the bundle's BundleActivator could not be loaded or
	 *                threw an exception.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle tries to change its own state.
	 */
	protected void suspend(boolean lock) throws BundleException {
		if (state == UNINSTALLED) {
			return;
		}
		beginStateChange();
		try {
			stopWorker(false);
		} finally {
			if (!lock) {
				completeStateChange();
			}
		}
	}
	/**
	 * Update this bundle. If the bundle is {@link #ACTIVE}, the bundle will
	 * be stopped before the update and started after the update successfully
	 * completes.
	 * 
	 * <p>
	 * The following steps are followed to update a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #ACTIVE}or {@link #STARTING}, the bundle
	 * is stopped as described in the {@link #stop()}method. If {@link #stop()}
	 * throws an exception, the exception is rethrown terminating the update.
	 * <li>The location for the new version of the bundle is determined from
	 * either the manifest header <code>Bundle-UpdateLocation</code> if
	 * available or the original location.
	 * <li>The location is interpreted in an implementation dependent way
	 * (typically as a URL) and the new version of the bundle is obtained from
	 * the location.
	 * <li>The new version of the bundle is installed. If the framework is
	 * unable to install the new version of the bundle, the original version of
	 * the bundle will be restored and a {@link BundleException}will be thrown
	 * after completion of the remaining steps.
	 * <li>The state of the bundle is set to {@link #INSTALLED}.
	 * <li>If the new version of the bundle was successfully installed, a
	 * {@link BundleEvent}of type {@link BundleEvent#UPDATED}is broadcast.
	 * <li>If the bundle was originally {@link #ACTIVE}, the updated bundle
	 * is started as described in the {@link #start()}method. If {@link #start()}
	 * throws an exception, a {@link FrameworkEvent}of type
	 * {@link FrameworkEvent#ERROR}is broadcast containing the exception.
	 * </ol>
	 * 
	 * <h5>Preconditions</h5>
	 * <ul>
	 * <li>getState() not in {{@link #UNINSTALLED}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #INSTALLED},{@link #RESOLVED},
	 * {@link #ACTIVE}}.
	 * <li>The bundle has been updated.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #INSTALLED},{@link #RESOLVED},
	 * {@link #ACTIVE}}.
	 * <li>Original bundle is still used, no update took place.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the update fails.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled or the bundle tries to
	 *                change its own state.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 * @see #stop()
	 * @see #start()
	 */
	public void update() throws BundleException {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			Debug.println("update location " + bundledata.getLocation());
		}
		framework.checkAdminPermission();
		checkValid();
		beginStateChange();
		try {
			updateWorker(new PrivilegedExceptionAction() {
				public Object run() throws BundleException {
					/* compute the update location */
					String updateLocation = bundledata.getLocation();
					if (bundledata.getManifest().get(Constants.BUNDLE_UPDATELOCATION) != null) {
						updateLocation = (String) bundledata.getManifest().get(Constants.BUNDLE_UPDATELOCATION);
						if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
							Debug.println("   from location: " + updateLocation);
						}
					}
					/* Map the identity to a URLConnection */
					URLConnection source = framework.adaptor.mapLocationToURLConnection(updateLocation);
					/* call the worker */
					updateWorkerPrivileged(source);
					return null;
				}
			});
		} catch (BundleException e) {
			if (!(e.getNestedException() instanceof NothingToUpdateException)) {
				throw e;
			}
			//There is no work to be done with this update.
			//Quietly return with no error.
		} finally {
			completeStateChange();
		}
	}
	/**
	 * Update this bundle from an InputStream.
	 * 
	 * <p>
	 * This method performs all the steps listed in {@link #update()}, except
	 * the bundle will be read in through the supplied <code>InputStream</code>,
	 * rather than a <code>URL</code>.
	 * 
	 * @param in
	 *            The InputStream from which to read the new bundle.
	 * @see #update()
	 */
	public void update(final InputStream in) throws BundleException {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			Debug.println("update location " + bundledata.getLocation());
			Debug.println("   from: " + in);
		}
		framework.checkAdminPermission();
		checkValid();
		beginStateChange();
		try {
			updateWorker(new PrivilegedExceptionAction() {
				public Object run() throws BundleException {
					/* Map the InputStream to a URLConnection */
					URLConnection source = new BundleSource(in);
					/* call the worker */
					updateWorkerPrivileged(source);
					return null;
				}
			});
		} finally {
			completeStateChange();
		}
	}
	/**
	 * Update worker. Assumes the caller has the state change lock.
	 */
	protected void updateWorker(PrivilegedExceptionAction action) throws BundleException {
		boolean bundleActive = false;
		Bundle host = null;
		if (isFragment()) {
			host = (Bundle) getHost();
			bundleActive = (host == null ? false : (host.state == ACTIVE));
		} else {
			bundleActive = (state == ACTIVE);
		}
		if (bundleActive) {
			try {
				if (isFragment()) {
					host.stopWorker(false);
				} else {
					stopWorker(false);
				}
			} catch (BundleException e) {
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
				if (state == ACTIVE) /* if the bundle is still active */{
					throw e;
				}
			}
		}
		try {
			AccessController.doPrivileged(action);
			framework.publishBundleEvent(BundleEvent.UPDATED, this);
		} catch (PrivilegedActionException pae) {
			throw (BundleException) pae.getException();
		} finally {
			if (bundleActive) {
				try {
					if (isFragment()) {
						host.startWorker(false);
					} else {
						startWorker(false);
					}
				} catch (BundleException e) {
					framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
				}
			}
		}
	}
	/**
	 * Update worker. Assumes the caller has the state change lock.
	 */
	protected void updateWorkerPrivileged(URLConnection source) throws BundleException {
		Bundle oldBundle = Bundle.createBundle(bundledata, framework);
		boolean reloaded = false;
		BundleOperation storage = framework.adaptor.updateBundle(this.bundledata, source);
		BundleRepository bundles = framework.getBundles();
		try {
			BundleData newBundleData = storage.begin();
			// Must call framework createBundle to check execution environment.
			Bundle newBundle = framework.createBundle(newBundleData);
			// Check for a bundle already installed with the same symbolicName
			// and version.
			String symbolicName = newBundle.getSymbolicName();
			Bundle installedBundle = symbolicName == null ? null : framework.getBundleByUniqueId(symbolicName, newBundle.getVersion().toString());
			if (installedBundle != null && installedBundle != this) {
				throw new BundleException(Msg.formatter.getString("BUNDLE_INSTALL_SAME_UNIQUEID", new Object[] {installedBundle.getSymbolicName(), installedBundle.getVersion(),  installedBundle.getLocation()}));
			}
			String[] nativepaths = framework.selectNativeCode(newBundle);
			if (nativepaths != null) {
				bundledata.installNativeCode(nativepaths);
			}
			boolean exporting;
			synchronized (bundles) {
				bundles.markDependancies();
				exporting = reload(newBundle);
			}
			reloaded = true; /*
			 * indicate we have loaded from the new version of
			 * the bundle
			 */
			storage.commit(exporting);
		} catch (BundleException e) {
			try {
				storage.undo();
				if (reloaded) /*
				 * if we loaded from the new version of the
				 * bundle
				 */{
					synchronized (bundles) {
						reload(oldBundle); /* revert to old version */
					}
				}
			} catch (BundleException ee) {
				/* if we fail to revert then we are in big trouble */
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, ee);
			}
			throw e;
		}
	}
	/**
	 * Uninstall this bundle.
	 * <p>
	 * This method removes all traces of the bundle, including any data in the
	 * persistent storage area provided for the bundle by the framework.
	 * 
	 * <p>
	 * The following steps are followed to uninstall a bundle:
	 * <ol>
	 * <li>If the bundle is {@link #UNINSTALLED}then an <code>IllegalStateException</code>
	 * is thrown.
	 * <li>If the bundle is {@link #ACTIVE}or {@link #STARTING}, the bundle
	 * is stopped as described in the {@link #stop()}method. If {@link #stop()}
	 * throws an exception, a {@link FrameworkEvent}of type
	 * {@link FrameworkEvent#ERROR}is broadcast containing the exception.
	 * <li>A {@link BundleEvent}of type {@link BundleEvent#UNINSTALLED}is
	 * broadcast.
	 * <li>The state of the bundle is set to {@link #UNINSTALLED}.
	 * <li>The bundle and the persistent storage area provided for the bundle
	 * by the framework, if any, is removed.
	 * </ol>
	 * 
	 * <h5>Preconditions</h5>
	 * <ul>
	 * <li>getState() not in {{@link #UNINSTALLED}}.
	 * </ul>
	 * <h5>Postconditons, no exceptions thrown</h5>
	 * <ul>
	 * <li>getState() in {{@link #UNINSTALLED}}.
	 * <li>The bundle has been uninstalled.
	 * </ul>
	 * <h5>Postconditions, when an exception is thrown</h5>
	 * <ul>
	 * <li>getState() not in {{@link #UNINSTALLED}}.
	 * <li>The Bundle has not been uninstalled.
	 * </ul>
	 * 
	 * @exception BundleException
	 *                If the uninstall failed.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled or the bundle tries to
	 *                change its own state.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 * @see #stop()
	 */
	public void uninstall() throws BundleException {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			Debug.println("uninstall location: " + bundledata.getLocation());
		}
		framework.checkAdminPermission();
		checkValid();
		beginStateChange();
		try {
			uninstallWorker(new PrivilegedExceptionAction() {
				public Object run() throws BundleException {
					uninstallWorkerPrivileged();
					return null;
				}
			});
		} finally {
			completeStateChange();
		}
	}
	/**
	 * Uninstall worker. Assumes the caller has the state change lock.
	 */
	protected void uninstallWorker(PrivilegedExceptionAction action) throws BundleException {
		boolean bundleActive = false;
		Bundle host = null;
		if (isFragment()) {
			host = (Bundle) getHost();
			bundleActive = (host == null ? false : (host.state == ACTIVE));
		} else {
			bundleActive = (state == ACTIVE);
		}
		if (bundleActive) {
			try {
				if (isFragment()) {
					host.stopWorker(true);
				} else {
					stopWorker(true);
				}
			} catch (BundleException e) {
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
			}
		}
		try {
			AccessController.doPrivileged(action);
		} catch (PrivilegedActionException pae) {
			if (bundleActive) /* if we stopped the bundle */{
				try {
					if (isFragment()) {
						host.startWorker(false);
					} else {
						startWorker(false);
					}
				} catch (BundleException e) {
					/*
					 * if we fail to start the original bundle then we are in
					 * big trouble
					 */
					framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
				}
				// set the bundleActive to false so that the finally will not
				// try
				// to start the bundle again.
				bundleActive = false;
			}
			throw (BundleException) pae.getException();
		} finally {
			if (isFragment() && bundleActive) {
				try {
					host.startWorker(false);
				} catch (BundleException e) {
					/*
					 * if we fail to start the original host bundle then we are
					 * in big trouble
					 */
					framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
				}
			}
		}
		framework.publishBundleEvent(BundleEvent.UNINSTALLED, this);
	}
	/**
	 * Uninstall worker. Assumes the caller has the state change lock.
	 */
	protected void uninstallWorkerPrivileged() throws BundleException {
		boolean unloaded = false;
		//cache the bundle's headers
		getHeaders();
		BundleOperation storage = framework.adaptor.uninstallBundle(this.bundledata);
		BundleRepository bundles = framework.getBundles();
		try {
			storage.begin();
			boolean exporting;
			synchronized (bundles) {
				bundles.markDependancies();
				bundles.remove(this); /* remove before calling unload */
				exporting = unload();
			}
			unloaded = true;
			storage.commit(exporting);
			close();
		} catch (BundleException e) {
			try {
				storage.undo();
				if (unloaded) /* if we unloaded the bundle */{
					synchronized (bundles) {
						load(); /* reload the bundle */
						bundles.add(this);
					}
				}
			} catch (BundleException ee) {
				/*
				 * if we fail to load the original bundle then we are in big
				 * trouble
				 */
				framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, ee);
			}
			throw e;
		}
	}
	/**
	 * Return the bundle's manifest headers and values from the manifest's
	 * preliminary section. That is all the manifest's headers and values prior
	 * to the first blank line.
	 * 
	 * <p>
	 * Manifest header names are case-insensitive. The methods of the returned
	 * <code>Dictionary</code> object will operate on header names in a
	 * case-insensitive manner.
	 * 
	 * <p>
	 * For example, the following manifest headers and values are included if
	 * they are present in the manifest:
	 * 
	 * <pre>
	 *  Bundle-Name
	 *  Bundle-Vendor
	 *  Bundle-Version
	 *  Bundle-Description
	 *  Bundle-DocURL
	 *  Bundle-ContactAddress
	 * </pre>
	 * 
	 * <p>
	 * This method will continue to return this information when the bundle is
	 * in the {@link #UNINSTALLED}state.
	 * 
	 * @return A <code>Dictionary</code> object containing the bundle's
	 *         manifest headers and values.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public Dictionary getHeaders() {
		return getHeaders(Locale.getDefault().toString());
	}
	/**
	 * Returns this bundle's Manifest headers and values. This method returns
	 * all the Manifest headers and values from the main section of the
	 * bundle's Manifest file; that is, all lines prior to the first blank
	 * line.
	 * 
	 * <p>
	 * Manifest header names are case-insensitive. The methods of the returned
	 * <tt>Dictionary</tt> object will operate on header names in a
	 * case-insensitive manner.
	 * 
	 * If a Manifest header begins with a '%', it will be evaluated with the
	 * specified properties file for the specied Locale.
	 * 
	 * <p>
	 * For example, the following Manifest headers and values are included if
	 * they are present in the Manifest file:
	 * 
	 * <pre>
	 *  Bundle-Name
	 *  Bundle-Vendor
	 *  Bundle-Version
	 *  Bundle-Description
	 *  Bundle-DocURL
	 *  Bundle-ContactAddress
	 * </pre>
	 * 
	 * <p>
	 * This method will continue to return Manifest header information while
	 * this bundle is in the <tt>UNINSTALLED</tt> state.
	 * 
	 * @return A <tt>Dictionary</tt> object containing this bundle's Manifest
	 *         headers and values.
	 * 
	 * @exception java.lang.SecurityException
	 *                If the caller does not have the <tt>AdminPermission</tt>,
	 *                and the Java Runtime Environment supports permissions.
	 */
	public Dictionary getHeaders(String localeString) {
		framework.checkAdminPermission();
		try {
			initializeManifestLocalization();
		} catch (BundleException e) {
			framework.publishFrameworkEvent(FrameworkEvent.ERROR, this, e);
			// return an empty dictinary.
			return new Hashtable();
		}
		return manifestLocalization.getHeaders(localeString);
	}
	/**
	 * Retrieve the bundle's unique identifier, which the framework assigned to
	 * this bundle when it was installed.
	 * 
	 * <p>
	 * The unique identifier has the following attributes:
	 * <ul>
	 * <li>It is unique and persistent.
	 * <li>The identifier is a long.
	 * <li>Once its value is assigned to a bundle, that value is not reused
	 * for another bundle, even after the bundle is uninstalled.
	 * <li>Its value does not change as long as the bundle remains installed.
	 * <li>Its value does not change when the bundle is updated
	 * </ul>
	 * 
	 * <p>
	 * This method will continue to return the bundle's unique identifier when
	 * the bundle is in the {@link #UNINSTALLED}state.
	 * 
	 * @return This bundle's unique identifier.
	 */
	public long getBundleId() {
		return (bundledata.getBundleID());
	}
	/**
	 * Retrieve the location identifier of the bundle. This is typically the
	 * location passed to
	 * {@link BundleContext#installBundle(String) BundleContext.installBundle}when the
	 * bundle was installed. The location identifier of the bundle may change
	 * during bundle update. Calling this method while framework is updating
	 * the bundle results in undefined behavior.
	 * 
	 * <p>
	 * This method will continue to return the bundle's location identifier
	 * when the bundle is in the {@link #UNINSTALLED}state.
	 * 
	 * @return A string that is the location identifier of the bundle.
	 * @exception java.lang.SecurityException
	 *                If the caller does not have {@link AdminPermission}
	 *                permission and the Java runtime environment supports
	 *                permissions.
	 */
	public String getLocation() {
		framework.checkAdminPermission();
		return (bundledata.getLocation());
	}
	/**
	 * Provides a list of {@link ServiceReference}s for the services
	 * registered by this bundle or <code>null</code> if the bundle has no
	 * registered services.
	 * 
	 * <p>
	 * The list is valid at the time of the call to this method, but the
	 * framework is a very dynamic environment and services can be modified or
	 * unregistered at anytime.
	 * 
	 * @return An array of {@link ServiceReference}or <code>null</code>.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled.
	 * @see ServiceRegistration
	 * @see ServiceReference
	 */
	public abstract org.osgi.framework.ServiceReference[] getRegisteredServices();
	/**
	 * Provides a list of {@link ServiceReference}s for the services this
	 * bundle is using, or <code>null</code> if the bundle is not using any
	 * services. A bundle is considered to be using a service if the bundle's
	 * use count for the service is greater than zero.
	 * 
	 * <p>
	 * The list is valid at the time of the call to this method, but the
	 * framework is a very dynamic environment and services can be modified or
	 * unregistered at anytime.
	 * 
	 * @return An array of {@link ServiceReference}or <code>null</code>.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled.
	 * @see ServiceReference
	 */
	public abstract org.osgi.framework.ServiceReference[] getServicesInUse();
	/**
	 * Determine whether the bundle has the requested permission.
	 * 
	 * <p>
	 * If the Java runtime environment does not supports permissions this
	 * method always returns <code>true</code>. The permission parameter is
	 * of type <code>Object</code> to avoid referencing the <code>java.security.Permission</code>
	 * class directly. This is to allow the framework to be implemented in Java
	 * environments which do not support permissions.
	 * 
	 * @param permission
	 *            The requested permission.
	 * @return <code>true</code> if the bundle has the requested permission
	 *         or <code>false</code> if the bundle does not have the
	 *         permission or the permission parameter is not an <code>instanceof java.security.Permission</code>.
	 * @exception java.lang.IllegalStateException
	 *                If the bundle has been uninstalled.
	 */
	public boolean hasPermission(Object permission) {
		checkValid();
		if (domain != null) {
			if (permission instanceof Permission) {
				return domain.implies((Permission) permission);
			}
			return false;
		}
		return true;
	}
	/**
	 * This method marks the bundle's state as changing so that other calls to
	 * start/stop/suspend/update/uninstall can wait until the state change is
	 * complete. If stateChanging is non-null when this method is called, we
	 * will wait for the state change to complete. If the timeout expires
	 * without changing state (this may happen if the state change is back up
	 * our call stack), a BundleException is thrown so that we don't wait
	 * forever.
	 * 
	 * A call to this method should be immediately followed by a try block
	 * whose finally block calls completeStateChange().
	 * 
	 * beginStateChange(); try { // change the bundle's state here... } finally {
	 * completeStateChange(); }
	 * 
	 * @exception org.osgi.framework.BundleException
	 *                if the bundles state is still changing after waiting for
	 *                the timeout.
	 */
	protected void beginStateChange() throws BundleException {
		synchronized (statechangeLock) {
			boolean doubleFault = false;
			while (true) {
				if (stateChanging == null) {
					stateChanging = Thread.currentThread();
					return;
				}
				if (doubleFault || (stateChanging == Thread.currentThread())) {
					throw new BundleException(Msg.formatter.getString("BUNDLE_STATE_CHANGE_EXCEPTION",getLocation(),stateChanging.getName()));	//$NON-NLS-1$
				}
				try {
					if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
						Debug.println(" Waiting for state to change in bundle " + this); 	//$NON-NLS-1$
					}
					long start = 0;
					if (Debug.DEBUG)
						start = System.currentTimeMillis();
					statechangeLock.wait(5000); /*
					 * wait for other thread to
					 * finish changing state
					 */
					if (Debug.DEBUG) {
						long end = System.currentTimeMillis();
						if (end - start > 0) {
							System.out.println("Waiting... : " + getSymbolicName() + ' ' + (end - start));
						}
					}
				} catch (InterruptedException e) {
				}
				doubleFault = true;
			}
		}
	}
	/**
	 * This method completes the bundle state change by setting stateChanging
	 * to null and notifying one waiter that the state change has completed.
	 */
	protected void completeStateChange() {
		synchronized (statechangeLock) {
			if (stateChanging != null) {
				stateChanging = null;
				statechangeLock.notify(); /*
				 * notify one waiting thread that the
				 * state change is complete
				 */
			}
		}
	}
	/**
	 * Return a string representation of this bundle.
	 * 
	 * @return String
	 */
	public String toString() {
		return (bundledata.getLocation() + " [" + getBundleId() + "]");
	}
	/**
	 * Answers an integer indicating the relative positions of the receiver and
	 * the argument in the natural order of elements of the receiver's class.
	 * 
	 * @return int which should be <0 if the receiver should sort before the
	 *         argument, 0 if the receiver should sort in the same position as
	 *         the argument, and >0 if the receiver should sort after the
	 *         argument.
	 * @param obj
	 *            another Bundle an object to compare the receiver to
	 * @exception ClassCastException
	 *                if the argument can not be converted into something
	 *                comparable with the receiver.
	 */
	public int compareTo(Object obj) {
		int slcomp = getStartLevel() - ((Bundle) obj).getStartLevel();
		if (slcomp != 0) {
			return slcomp;
		}
		long idcomp = getBundleId() - ((Bundle) obj).getBundleId();
		return (idcomp < 0L) ? -1 : ((idcomp > 0L) ? 1 : 0);
	}
	/**
	 * This method checks that the bundle is not uninstalled. If the bundle is
	 * uninstalled, an IllegalStateException is thrown.
	 * 
	 * @exception java.lang.IllegalStateException
	 *                If the bundle is uninstalled.
	 */
	protected void checkValid() {
		if (state == UNINSTALLED) {
			throw new IllegalStateException(Msg.formatter.getString("BUNDLE_UNINSTALLED_EXCEPTION"));
		}
	}
	/**
	 * Get the bundle's ProtectionDomain.
	 * 
	 * @return bundle's ProtectionDomain.
	 */
	protected ProtectionDomain getProtectionDomain() {
		return domain;
	}
	/**
	 * The bundle must unresolve the permissions in these packages.
	 * 
	 * @param unresolvedPackages
	 *            A list of the package which have been unresolved as a result
	 *            of a packageRefresh
	 */
	protected void unresolvePermissions(Hashtable unresolvedPackages) {
		if (domain != null) {
			BundlePermissionCollection collection = (BundlePermissionCollection) domain.getPermissions();
			if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
				Debug.println("Unresolving permissions in bundle " + this);
			}
			collection.unresolvePermissions(unresolvedPackages);
		}
	}
	public org.osgi.framework.Bundle[] getFragments() {
		checkValid();
		return null;
	}
	public boolean isFragment() {
		return false;
	}
	public org.osgi.framework.Bundle getHost() {
		checkValid();
		return null;
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.framework.Bundle#findClass(java.lang.String)
	 */
	public Class loadClass(String classname) throws ClassNotFoundException {
		return loadClass(classname, true);
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.framework.Bundle#getResourcePaths(java.lang.String)
	 */
	public Enumeration getEntryPaths(final String path) {
		try {
			framework.checkAdminPermission();
		} catch (SecurityException e) {
			return null;
		}
		checkValid();
		if (bundledata == null) {
			if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
				Debug.println("Bundle.getResourcePaths(" + path + ") called when bundledata == null: " + this);
				Debug.printStackTrace(new Exception("Stack trace"));
			}
			return (null);
		}
		return (Enumeration) AccessController.doPrivileged(new PrivilegedAction() {
			public Object run() {
				return bundledata.getEntryPaths(path);
			}
		});
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.framework.Bundle#getFile(java.lang.String)
	 */
	public URL getEntry(String fileName) {
		try {
			framework.checkAdminPermission();
		} catch (SecurityException e) {
			return null;
		}
		checkValid();
		if (bundledata == null) {
			if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
				Debug.println("Bundle.getFile(" + fileName + ") called when bundledata == null: " + this);
				Debug.printStackTrace(new Exception("Stack trace"));
			}
			return (null);
		}
		if (System.getSecurityManager() == null)
			return bundledata.getEntry(fileName);
		final String ffileName = fileName;
		return (URL) AccessController.doPrivileged(new PrivilegedAction() {
			public Object run() {
				return bundledata.getEntry(ffileName);
			}
		});
	}
	public String getSymbolicName() {
		return bundledata.getSymbolicName();
	}
	public BundleData getBundleData() {
		return bundledata;
	}
	public Version getVersion() {
		return bundledata.getVersion();
	}
	protected BundleDescription getBundleDescription() {
		return framework.adaptor.getState().getBundle(getBundleId());
	}
	protected int getStartLevel(){
		return bundledata.getStartLevel();
	}
	public abstract BundleLoader getBundleLoader();
	/**
	 * Mark this bundle as resolved.
	 */
	protected void resolve() {
		if (domain != null && !checkPermissions()) {
			state = INSTALLED;
			return;
		}
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			if ((state & (INSTALLED)) == 0) {
				Debug.println("Bundle.resolve called when state != INSTALLED: " + this);
				Debug.printStackTrace(new Exception("Stack trace"));
			}
		}
		if (state == INSTALLED) {
			state = RESOLVED;
			// Do not publish RESOLVED event here.  This is done by caller 
			// to resolve if appropriate.
		}
	}
	protected abstract boolean unresolve() throws BundleException;
	/**
	 * Return the current context for this bundle.
	 * 
	 * @return BundleContext for this bundle.
	 */
	abstract protected BundleContext getContext();
	protected String getResolutionFailureMessage() {
		String defaultMessage = Msg.formatter.getString("BUNDLE_UNRESOLVED_EXCEPTION");
		// don't spend time if debug info is not needed
		if (!Debug.DEBUG) {
			return defaultMessage;
		}
		if (permissionMsg != null) {
			return permissionMsg;	// do not null this field out until a successful resolve is done.
		}
		BundleDescription bundleDescription = getBundleDescription();
		if (bundleDescription == null) {
			return defaultMessage;
		}
		// just a sanity check - this would be an inconsistency between the
		// framework and the state
		if (bundleDescription.isResolved()) {
			throw new IllegalStateException("bundle *is* resolved");
		}
		VersionConstraint[] unsatisfied = framework.adaptor.getPlatformAdmin().getStateHelper().getUnsatisfiedConstraints(bundleDescription);
		if (unsatisfied.length == 0) {
			return Msg.formatter.getString("BUNDLE_UNRESOLVED_NOT_CHOSEN_EXCEPTION");
		}
		StringBuffer missing = new StringBuffer();
		for (int i = 0; i < unsatisfied.length; i++) {
			if (unsatisfied[i] instanceof PackageSpecification) {
				missing.append(Msg.formatter.getString("BUNDLE_UNRESOLVED_PACKAGE", toString(unsatisfied[i])));
			} else if (unsatisfied[i] instanceof BundleSpecification) {
				missing.append(Msg.formatter.getString("BUNDLE_UNRESOLVED_BUNDLE", toString(unsatisfied[i])));
			} else {
				missing.append(Msg.formatter.getString("BUNDLE_UNRESOLVED_HOST", toString(unsatisfied[i])));
			}
			missing.append(',');
		}
		missing.deleteCharAt(missing.length() - 1);
		return Msg.formatter.getString("BUNDLE_UNRESOLVED_UNSATISFIED_CONSTRAINT_EXCEPTION", missing.toString());
	}
	private String toString(VersionConstraint constraint) {
		org.eclipse.osgi.service.resolver.Version versionSpec = constraint.getVersionSpecification();
		if (versionSpec == null)
			return constraint.getName();
		return constraint.getName() + '_' + versionSpec;
	}
	public int getKeyHashCode() {
		return (int) getBundleId();
	}
	public boolean compare(KeyedElement other) {
		return getBundleId() == ((Bundle) other).getBundleId();
	}
	public Object getKey() {
		return new Long(getBundleId());
	}
	protected boolean checkPermissions() {
		permissionMsg = null;
		BundleDescription bundleDesc = getBundleDescription();
		if (bundleDesc == null)
			return false;
		PackageSpecification[] pkgs = bundleDesc.getPackages();
		for (int i = 0; i < pkgs.length; i++) {
			// check to make sure the exporter has permissions
			BundleDescription supplier = pkgs[i].getSupplier();
			Bundle supplierBundle = supplier == null ? null : framework.getBundle(supplier.getBundleId());
			if (supplierBundle == null || !supplierBundle.checkExportPackagePermission(pkgs[i].getName())) {
				permissionMsg = Msg.formatter.getString("BUNDLE_PERMISSION_EXCEPTION_EXPORT", supplierBundle, pkgs[i].getName());
				return false;
			}
			// check to make sure the importer has permissions
			if (!checkImportPackagePermission(pkgs[i].getName())) {
				permissionMsg = Msg.formatter.getString("BUNDLE_PERMISSION_EXCEPTION_IMPORT", this, pkgs[i].getName());
				return false;
			}
		}
		BundleSpecification[] bundles = bundleDesc.getRequiredBundles();
		for (int i = 0; i < bundles.length; i++) {
			// check to make sure the provider has permissions
			BundleDescription supplier = bundles[i].getSupplier();
			Bundle supplierBundle = supplier == null ? null : framework.getBundle(supplier.getBundleId());
			if (supplierBundle == null || !supplierBundle.checkProvideBundlePermission(bundles[i].getName())) {
				permissionMsg = Msg.formatter.getString("BUNDLE_PERMISSION_EXCEPTION_PROVIDE", supplierBundle, bundles[i].getName());
				return false;
			}
			// check to make sure the requirer has permissions
			if (!checkRequireBundlePermission(bundles[i].getName())) {
				permissionMsg = Msg.formatter.getString("BUNDLE_PERMISSION_EXCEPTION_REQUIRE", this, bundles[i].getName());
				return false;
			}
		}
		HostSpecification host = bundleDesc.getHost();
		if (host != null) {
			// check to make sure the host has permissions
			BundleDescription supplier = host.getSupplier();
			Bundle supplierBundle = supplier == null ? null : framework.getBundle(supplier.getBundleId());
			if (supplierBundle == null || !supplierBundle.checkFragmentHostPermission(host.getName())) {
				permissionMsg = Msg.formatter.getString("BUNDLE_PERMISSION_EXCEPTION_HOST", supplierBundle, host.getName());
				return false;
			}
			// check to make sure the fragment has permissions
			if (!checkFragmentBundlePermission(host.getName())) {
				permissionMsg = Msg.formatter.getString("BUNDLE_PERMISSION_EXCEPTION_FRAGMENT", this, host.getName());
				return false;
			}
		}
		return true;
	}
	protected boolean checkExportPackagePermission(String pkgName) {
		if (domain != null)
			return domain.implies(new PackagePermission(pkgName, PackagePermission.EXPORT));
		return true;
	}
	protected boolean checkProvideBundlePermission(String symbolicName) {
		if (domain != null)
			return domain.implies(new BundlePermission(symbolicName, BundlePermission.PROVIDE_BUNDLE));
		return true;
	}
	protected boolean checkImportPackagePermission(String pkgName) {
		if (domain != null)
			return domain.implies(new PackagePermission(pkgName, PackagePermission.IMPORT));
		return true;
	}
	protected boolean checkRequireBundlePermission(String symbolicName) {
		if (domain != null)
			return domain.implies(new BundlePermission(symbolicName, BundlePermission.REQUIRE_BUNDLE));
		return true;
	}
	protected boolean checkFragmentHostPermission(String symbolicName) {
		if (domain != null)
			return domain.implies(new BundlePermission(symbolicName, BundlePermission.FRAGMENT_HOST));
		return true;
	}
	protected boolean checkFragmentBundlePermission(String symbolicName) {
		if (domain != null)
			return domain.implies(new BundlePermission(symbolicName, BundlePermission.FRAGMENT_BUNDLE));
		return true;
	}
	/* This method is used by the Bundle Localization Service to obtain
	 * a ResourceBundle that resides in a bundle.  This is not an OSGi
	 * defined method for org.osgi.framework.Bundle
	 * 
	 */
	public ResourceBundle getResourceBundle(String localeString) {
		try {
			initializeManifestLocalization();
		} catch (BundleException ex) {
			return (null);
		}
		if (localeString == null) {
			localeString = Locale.getDefault().toString();
		}
		return manifestLocalization.getResourceBundle(localeString);
	}
	private void initializeManifestLocalization() throws BundleException {
		if (manifestLocalization == null) {
			Dictionary rawHeaders;
			rawHeaders = bundledata.getManifest();
			manifestLocalization = new ManifestLocalization(this, rawHeaders);
		}
	}
	public boolean testStateChanging(Object thread) {
		return stateChanging == thread;
	}
	public Object getStateChangeLock() {
		return statechangeLock;
	}
	public Thread getStateChanging() {
		return stateChanging;
	}
}