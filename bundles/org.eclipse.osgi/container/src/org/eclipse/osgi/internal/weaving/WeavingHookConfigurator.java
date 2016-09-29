/*******************************************************************************
 * Copyright (c) 2010, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.weaving;

import java.util.*;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.eclipse.osgi.internal.hookregistry.ClassLoaderHook;
import org.eclipse.osgi.internal.loader.BundleLoader;
import org.eclipse.osgi.internal.loader.ModuleClassLoader;
import org.eclipse.osgi.internal.loader.classpath.ClasspathEntry;
import org.eclipse.osgi.internal.loader.classpath.ClasspathManager;
import org.eclipse.osgi.internal.serviceregistry.ServiceRegistry;
import org.eclipse.osgi.storage.bundlefile.BundleEntry;
import org.osgi.framework.*;

public class WeavingHookConfigurator extends ClassLoaderHook {
	// holds the map of black listed hooks.  Use weak map to avoid pinning and simplify cleanup.
	private final Map<ServiceRegistration<?>, Boolean> blackList = Collections.synchronizedMap(new WeakHashMap<ServiceRegistration<?>, Boolean>());
	// holds the stack of WovenClass objects currently being used to define classes
	private final ThreadLocal<List<WovenClassImpl>> wovenClassStack = new ThreadLocal<>();

	private final EquinoxContainer container;

	public WeavingHookConfigurator(EquinoxContainer container) {
		this.container = container;
	}

	private ServiceRegistry getRegistry() {
		return container.getServiceRegistry();
	}

	public byte[] processClass(String name, byte[] classbytes, ClasspathEntry classpathEntry, BundleEntry entry, ClasspathManager manager) {
		ServiceRegistry registry = getRegistry();
		if (registry == null)
			return null; // no registry somehow we are loading classes before the registry has been created
		ModuleClassLoader classLoader = manager.getClassLoader();
		BundleLoader loader = classLoader.getBundleLoader();
		// create a woven class object and add it to the thread local stack
		WovenClassImpl wovenClass = new WovenClassImpl(name, classbytes, entry, classpathEntry, loader, container, blackList);
		List<WovenClassImpl> wovenClasses = wovenClassStack.get();
		if (wovenClasses == null) {
			wovenClasses = new ArrayList<>(6);
			wovenClassStack.set(wovenClasses);
		}
		wovenClasses.add(wovenClass);
		// call the weaving hooks
		try {
			return wovenClass.callHooks();
		} catch (Throwable t) {
			ServiceRegistration<?> errorHook = wovenClass.getErrorHook();
			Bundle errorBundle = errorHook != null ? errorHook.getReference().getBundle() : manager.getGeneration().getRevision().getBundle();
			container.getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, errorBundle, t);
			// fail hard with a class loading error
			ClassFormatError error = new ClassFormatError("Unexpected error from weaving hook."); //$NON-NLS-1$
			error.initCause(t);
			throw error;
		}
	}

	public void recordClassDefine(String name, Class<?> clazz, byte[] classbytes, ClasspathEntry classpathEntry, BundleEntry entry, ClasspathManager manager) {
		// here we assume the stack contans a woven class with the same name as the class we are defining.
		List<WovenClassImpl> wovenClasses = wovenClassStack.get();
		if (wovenClasses == null || wovenClasses.size() == 0)
			return;
		WovenClassImpl wovenClass = wovenClasses.remove(wovenClasses.size() - 1);
		// inform the woven class about the class that was defined.
		wovenClass.setWeavingCompleted(clazz);
	}

}
