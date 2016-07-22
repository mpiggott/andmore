/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.andmore.android.common.utilities;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.eclipse.andmore.android.common.log.AndmoreLogger;
import org.eclipse.andmore.android.common.utilities.i18n.UtilitiesNLS;
import org.eclipse.andmore.android.common.utilities.ui.LoginPasswordDialogCreator;
import org.eclipse.core.internal.net.ProxyManager;
import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.ui.internal.net.auth.NetAuthenticator;

/**
 * Class for opening an input stream with the given URL.
 */
@SuppressWarnings("restriction")
public class HttpUtils {

	// map of credentials authentication so the user is not repeatedly asked for
	// them
	private static final Map<String, Credentials> unconfirmedAuthenticationRealmCache = new HashMap<String, Credentials>();

	private static final CredentialsProvider provider = new BasicCredentialsProvider() {
		@Override
		public Credentials getCredentials(AuthScope authscope) {
			AndmoreLogger.debug(HttpUtils.class, "Client requested authentication; retrieving credentials"); //$NON-NLS-1$

			Credentials credentials = super.getCredentials(authscope);

			if (credentials == null) {
				AndmoreLogger.debug(HttpUtils.class, "Credentials not found; prompting user for login/password"); //$NON-NLS-1$

				LoginPasswordDialogCreator dialogCreator = new LoginPasswordDialogCreator(authscope.getHost());
				if (dialogCreator.openLoginPasswordDialog() == LoginPasswordDialogCreator.OK) {

					credentials = new UsernamePasswordCredentials(dialogCreator.getTypedLogin(),
							dialogCreator.getTypedPassword());
					unconfirmedAuthenticationRealmCache.put(authscope.getRealm(), credentials);
				}
			}
			return credentials;
		}
	};

	private HttpGet getMethod;

	/**
	 * Retrieves an open InputStream with the contents of the file pointed by
	 * the given url.
	 * 
	 * @param url
	 *            The address from where to retrieve the InputStream
	 * @param monitor
	 *            The monitor to progress while accessing the file
	 * 
	 * @return The open InputStream object, or <code>null</code> if no file was
	 *         found
	 * 
	 * @throws IOException
	 *             if some error occurs with the network communication
	 */
	public InputStream getInputStreamForUrl(String url, IProgressMonitor monitor) throws IOException {
		return getInputStreamForUrl(url, monitor, true);
	}

	private InputStream getInputStreamForUrl(final String url, IProgressMonitor monitor, boolean returnStream)
			throws IOException {
		final SubMonitor subMonitor = SubMonitor.convert(monitor);

		subMonitor.beginTask(UtilitiesNLS.HttpUtils_MonitorTask_PreparingConnection, 300);

		AndmoreLogger.debug(HttpUtils.class, "Verifying proxy usage for opening http connection"); //$NON-NLS-1$

		// Try to retrieve proxy configuration to use if necessary
		IProxyService proxyService = ProxyManager.getProxyManager();
		IProxyData proxyData = null;
		if (proxyService.isProxiesEnabled() || proxyService.isSystemProxiesEnabled()) {
			Authenticator.setDefault(new NetAuthenticator());
			if (url.startsWith("https")) {
				proxyData = proxyService.getProxyData(IProxyData.HTTPS_PROXY_TYPE);
				AndmoreLogger.debug(HttpUtils.class, "Using https proxy"); //$NON-NLS-1$
			} else if (url.startsWith("http")) {
				proxyData = proxyService.getProxyData(IProxyData.HTTP_PROXY_TYPE);
				AndmoreLogger.debug(HttpUtils.class, "Using http proxy"); //$NON-NLS-1$
			} else {
				AndmoreLogger.debug(HttpUtils.class, "Not using any proxy"); //$NON-NLS-1$
			}
		}

		HttpClientBuilder clientBuilder = HttpClients.custom();

		// If there is proxy data, work with it
		if (proxyData != null) {
			if (proxyData.getHost() != null && (IProxyData.HTTP_PROXY_TYPE.equals(proxyData.getType())
					|| IProxyData.HTTPS_PROXY_TYPE.equals(proxyData.getType()))) {

				String scheme = IProxyData.HTTPS_PROXY_TYPE.equals(proxyData.getType()) ? "https" : "http";
				HttpHost proxyHost = new HttpHost(proxyData.getHost(), proxyData.getPort(), scheme);
				// Sets proxy host and port, if any
				clientBuilder.setRoutePlanner(new DefaultProxyRoutePlanner(proxyHost));

				if (proxyData.getUserId() != null && proxyData.getUserId().trim().length() > 0) {
					// Sets proxy user and password, if any
					Credentials cred = new UsernamePasswordCredentials(proxyData.getUserId(),
							proxyData.getPassword() == null ? "" : proxyData.getPassword()); //$NON-NLS-1$

					CredentialsProvider credsProvider = new BasicCredentialsProvider();
					credsProvider.setCredentials(new AuthScope(proxyHost), cred);
					clientBuilder.setDefaultCredentialsProvider(credsProvider);
				}
			}

		}

		clientBuilder.setDefaultCredentialsProvider(provider);

		// Set method to be retried three times in case of error
		clientBuilder.setRetryHandler(new DefaultHttpRequestRetryHandler(1, false));

		// Creates the http client and the method to be executed
		HttpClient client = clientBuilder.build();

		InputStream streamForUrl = null;
		getMethod = new HttpGet(url);

		subMonitor.worked(100);
		subMonitor.setTaskName(UtilitiesNLS.HttpUtils_MonitorTask_ContactingSite);
		AndmoreLogger.info(HttpUtils.class, "Attempting to make a connection"); //$NON-NLS-1$
		HttpResponse response = client.execute(getMethod);

		if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
			AndmoreLogger.debug(HttpUtils.class, "Http connection suceeded"); //$NON-NLS-1$

			subMonitor.setTaskName(UtilitiesNLS.HttpUtils_MonitorTask_RetrievingSiteContent);

			URI uri = URI.create(url);
			AuthScope scope = new AuthScope(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()));
			if (unconfirmedAuthenticationRealmCache.get(scope.getRealm()) != null) {
				provider.setCredentials(scope, unconfirmedAuthenticationRealmCache.get(scope.getRealm()));
				unconfirmedAuthenticationRealmCache.remove(scope.getRealm());
			}

			AndmoreLogger.info(HttpUtils.class, "Retrieving site content"); //$NON-NLS-1$

			// if the stream should not be returned (ex: only testing
			// the connection is
			// possible), then null will be returned
			if (returnStream) {
				streamForUrl = response.getEntity().getContent();
			}

			subMonitor.worked(100);
		}

		subMonitor.done();

		return streamForUrl;
	}

	/**
	 * Check if a connection with the given URL can be established.
	 * 
	 * @param url
	 *            The URL to test the connection.
	 * 
	 * @return <code>true</code> if the connection can be established;
	 *         <code>false</code> otherwise
	 */
	public boolean isConnectionOk(String url) {
		try {
			getInputStreamForUrl(url, null, false);
			// no need to release connection since the stream has not been
			// retrieved
			// if the code above does not throw any exception, the connection is
			// fine
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Release the http connection after users finished reading the InputStream
	 * provided by the {@link #getInputStreamForUrl(String, IProgressMonitor)}
	 * method.
	 */
	public void releaseConnection() {
		if (getMethod != null) {
			Thread t = new Thread() {
				/*
				 * (non-Javadoc)
				 * 
				 * @see java.lang.Thread#run()
				 */
				@Override
				public void run() {
					getMethod.releaseConnection();
				}
			};
			t.start();

		}
	}
}
