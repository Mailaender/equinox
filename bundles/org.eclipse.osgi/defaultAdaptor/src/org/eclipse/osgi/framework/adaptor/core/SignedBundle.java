/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.framework.adaptor.core;

import java.io.IOException;

public abstract class SignedBundle extends BundleFile {
	/**
	 * Sets the BundleFile for this singed bundle. It will extract
	 * signatures and digests from the bundle file and validate input streams
	 * before using them from the bundle file.
	 * 
	 * @param bundleFile the BundleFile to extract elements from.
	 * @throws IOException
	 */
	public abstract void setBundleFile(BundleFile bundleFile) throws IOException;

	/**
	 * Retrieves the certificate chains that signed this repository. Only
	 * validated certificate chains are returned. Each element of the returned
	 * array will contain a chain of distinguished names (DNs) separated by
	 * semicolons. The first DN is the signer and the last is the root
	 * Certificate Authority.
	 * 
	 * @return the certificate chains that signed this repository.
	 */
	public abstract String[] getSigningCertificateChains();

	/**
	 * Matches a distinguished name chain against a pattern of a distinguished
	 * name chain.
	 * 
	 * @param dnChain the distingueshed name chanin
	 * @param pattern the pattern of distinguished name (DN) chains to match
	 *        against the dnChain. Wildcards "*" can be used in three cases:
	 *        <ol>
	 *        <li>As a DN. In this case, the DN will consist of just the "*".
	 *        It will match zero or more DNs. For example, "cn=me,c=US;*;cn=you"
	 *        will match "cn=me,c=US";cn=you" and
	 *        "cn=me,c=US;cn=her,c=CA;cn=you".
	 *        <li>As a DN prefix. In this case, the DN must start with "*,".
	 *        The wild card will match zero or more RDNs at the start of a DN.
	 *        For example, "*,cn=me,c=US;cn=you" will match "cn=me,c=US";cn=you"
	 *        and "ou=my org unit,o=my org,cn=me,c=US;cn=you"</li>
	 *        <li>As a value. In this case the value of a name value pair in an
	 *        RDN will be a "*". The wildcard will match any value for the given
	 *        name. For example, "cn=*,c=US;cn=you" will match
	 *        "cn=me,c=US";cn=you" and "cn=her,c=US;cn=you", but it will not
	 *        match "ou=my org unit,c=US;cn=you". If the wildcard does not occur
	 *        by itself in the value, it will not be used as a wildcard. In
	 *        other words, "cn=m*,c=US;cn=you" represents the common name of
	 *        "m*" not any common name starting with "m".</li>
	 *        </ol>
	 * @return true if dnChain matches the pattern. A value of false is returned
	 * bundle signing is not supported.
	 * @throws IllegalArgumentException
	 */
	public abstract boolean matchDNChain(String dnChain, String pattern);
}
