/*
 * TeleStax, Open Source Cloud Communications  Copyright 2012. 
 * and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.protocols.sctp.multiclient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javolution.text.TextBuilder;
import javolution.util.FastList;
import javolution.util.FastMap;
import javolution.xml.XMLObjectReader;
import javolution.xml.XMLObjectWriter;
import javolution.xml.stream.XMLStreamException;

import org.apache.log4j.Logger;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.Management;
import org.mobicents.protocols.api.ManagementEventListener;
import org.mobicents.protocols.api.Server;
import org.mobicents.protocols.api.ServerListener;
import org.mobicents.protocols.sctp.AssociationMap;

/**
 * This class is a partial implementation of the Management interface of the sctp-api. 
 * It is partial because it does not support the whole functionality of the interface instead
 * it extends the capabilities of the implementition provided by org.mobicents.protocols.sctp package
 * with the capability to use One-To-Many type SCTP client associations.
 * 
 * Therefore the following functionality is not supported by this class:
 * 		server type associations
 * 		TCP ipChannelType
 * 
 * @author amit bhayani
 * @author alerant appngin
 * 
 *  MultiManagementImpl is a limited implemention OneToMany client associations.
 *   
 */
public class MultiManagementImpl implements Management {

	private static final Logger logger = Logger.getLogger(MultiManagementImpl.class);

	private static final String SCTP_PERSIST_DIR_KEY = "sctp.persist.dir";
	private static final String USER_DIR_KEY = "user.dir";
	private static final String PERSIST_FILE_NAME = "sctp.xml";
	private static final String ASSOCIATIONS = "associations";
	
    private static final String CONNECT_DELAY_PROP = "connectdelay";
    private static final String SINGLE_THREAD_PROP = "singlethread";
    private static final String WORKER_THREADS_PROP = "workerthreads";

	private final TextBuilder persistFile = TextBuilder.newInstance();

	protected static final MultiSctpXMLBinding binding = new MultiSctpXMLBinding();
	protected static final String TAB_INDENT = "\t";
	private static final String CLASS_ATTRIBUTE = "type";

	private final String name;

	protected String persistDir = null;

	protected AssociationMap<String, Association> associations = new AssociationMap<String, Association>();

	private FastList<MultiChangeRequest> pendingChanges = new FastList<MultiChangeRequest>();

	// Create a new selector
	private Selector socketSelector = null;

	private MultiSelectorThread selectorThread = null;

	static final int DEFAULT_IO_THREADS = Runtime.getRuntime().availableProcessors() * 2;

	private int workerThreads = DEFAULT_IO_THREADS;

	private boolean singleThread = true;

	private int workerThreadCount = 0;

	// Maximum IO Errors tolerated by Socket. After this the Socket will be
	// closed and attempt will be made to open again
	private int maxIOErrors = 3;

	private int connectDelay = 5000;

	private ExecutorService[] executorServices = null;

	private FastList<ManagementEventListener> managementEventListeners = new FastList<ManagementEventListener>();

	private volatile boolean started = false;
	
	private final MultiChannelController multiChannelController = new MultiChannelController(this);

	public MultiManagementImpl(String name) throws IOException {
		this.name = name;
		binding.setClassAttribute(CLASS_ATTRIBUTE);		
		binding.setAlias(OneToManyAssociationImpl.class, "association");
		binding.setAlias(String.class, "string");
		this.socketSelector = SelectorProvider.provider().openSelector();
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	public String getPersistDir() {
		return persistDir;
	}

	public void setPersistDir(String persistDir) {
		this.persistDir = persistDir;
	}

	/**
	 * @return the connectDelay
	 */
	public int getConnectDelay() {
		return connectDelay;
	}

	/**
	 * @param connectDelay
	 *            the connectDelay to set
	 */
	public void setConnectDelay(int connectDelay) {
		this.connectDelay = connectDelay;
		
		this.store();
	}

	/**
	 * @return the workerThreads
	 */
	public int getWorkerThreads() {
		return workerThreads;
	}

	/**
	 * @param workerThreads
	 *            the workerThreads to set
	 */
	public void setWorkerThreads(int workerThreads) {
		if (workerThreads < 1) {
			workerThreads = DEFAULT_IO_THREADS;
		}
		this.workerThreads = workerThreads;

		this.store();
	}

	/**
	 * @return the maxIOErrors
	 */
	public int getMaxIOErrors() {
		return maxIOErrors;
	}

	/**
	 * @param maxIOErrors
	 *            the maxIOErrors to set
	 */
	public void setMaxIOErrors(int maxIOErrors) {
		if (maxIOErrors < 1) {
			maxIOErrors = 1;
		}
		this.maxIOErrors = maxIOErrors;
	}

	/**
	 * @return the singleThread
	 */
	public boolean isSingleThread() {
		return singleThread;
	}

	/**
	 * @param singleThread
	 *            the singleThread to set
	 */
	public void setSingleThread(boolean singleThread) {
		this.singleThread = singleThread;
		
		this.store();
	}

	protected FastList<ManagementEventListener> getManagementEventListeners() {
		return managementEventListeners;
	}

	public void addManagementEventListener(ManagementEventListener listener) {
		synchronized (this) {
			if (this.managementEventListeners.contains(listener))
				return;

			FastList<ManagementEventListener> newManagementEventListeners = new FastList<ManagementEventListener>();
			newManagementEventListeners.addAll(this.managementEventListeners);
			newManagementEventListeners.add(listener);
			this.managementEventListeners = newManagementEventListeners;
		}
	}

	public void removeManagementEventListener(ManagementEventListener listener) {
		synchronized (this) {
			if (!this.managementEventListeners.contains(listener))
				return;

			FastList<ManagementEventListener> newManagementEventListeners = new FastList<ManagementEventListener>();
			newManagementEventListeners.addAll(this.managementEventListeners);
			newManagementEventListeners.remove(listener);
			this.managementEventListeners = newManagementEventListeners;
		}
	}

	protected MultiChannelController getMultiChannelController() {
		return multiChannelController;
	}

	public void start() throws Exception {

		if (this.started) {
			logger.warn(String.format("management=%s is already started", this.name));
			return;
		}

		synchronized (this) {
			this.persistFile.clear();

			if (persistDir != null) {
				this.persistFile.append(persistDir).append(File.separator).append(this.name).append("_").append(PERSIST_FILE_NAME);
			} else {
				persistFile.append(System.getProperty(SCTP_PERSIST_DIR_KEY, System.getProperty(USER_DIR_KEY))).append(File.separator).append(this.name)
						.append("_").append(PERSIST_FILE_NAME);
			}

			logger.info(String.format("SCTP configuration file path %s", persistFile.toString()));

			try {
				this.load();
			} catch (FileNotFoundException e) {
				logger.warn(String.format("Failed to load the SCTP configuration file. \n%s", e.getMessage()));
			}

			if (!this.singleThread) {
				// If not single thread model we create worker threads
				this.executorServices = new ExecutorService[this.workerThreads];
				for (int i = 0; i < this.workerThreads; i++) {
					this.executorServices[i] = Executors.newSingleThreadExecutor();
				}
			}
			this.selectorThread = new MultiSelectorThread(this.socketSelector, this);
			this.selectorThread.setStarted(true);

			(new Thread(this.selectorThread)).start();

			this.started = true;

			if (logger.isInfoEnabled()) {
				logger.info(String.format("Started SCTP Management=%s WorkerThreads=%d SingleThread=%s", this.name,
						(this.singleThread ? 0 : this.workerThreads), this.singleThread));
			}

			for (ManagementEventListener lstr : managementEventListeners) {
				try {
					lstr.onServiceStarted();
				} catch (Throwable ee) {
					logger.error("Exception while invoking onServiceStarted", ee);
				}
			}
		}
	}

	public void stop() throws Exception {
		
		if (!this.started) {
			logger.warn(String.format("management=%s is already stopped", this.name));
			return;
		}

		for (ManagementEventListener lstr : managementEventListeners) {
			try {
				lstr.onServiceStopped();
			} catch (Throwable ee) {
				logger.error("Exception while invoking onServiceStopped", ee);
			}
		}

		// We store the original state first
		this.store();

		// Stop all associations
		FastMap<String, Association> associationsTemp = this.associations;
		for (FastMap.Entry<String, Association> n = associationsTemp.head(), end = associationsTemp.tail(); (n = n.getNext()) != end;) {
			Association associationTemp = n.getValue();
			if (associationTemp.isStarted()) {
				((OneToManyAssociationImpl) associationTemp).stop();
			}
		}

		if (this.executorServices != null) {
			for (int i = 0; i < this.executorServices.length; i++) {
				this.executorServices[i].shutdown();
			}
		}

		this.selectorThread.setStarted(false);
		this.socketSelector.wakeup(); // Wakeup selector so SelectorThread dies

		// waiting till stopping associations
		for (int i1 = 0; i1 < 20; i1++) {
			boolean assConnected = false;
			for (FastMap.Entry<String, Association> n = this.associations.head(), end = this.associations.tail(); (n = n.getNext()) != end;) {
				Association associationTemp = n.getValue();
				if (associationTemp.isConnected()) {
					assConnected = true;
					break;
				}
			}
			if (!assConnected)
				break;
			Thread.sleep(100);
		}

		// Graceful shutdown for each of Executors
		if (this.executorServices != null) {
			for (int i = 0; i < this.executorServices.length; i++) {
				if (!this.executorServices[i].isTerminated()) {
					if (logger.isInfoEnabled()) {
						logger.info("Waiting for worker thread to die gracefully ....");
					}
					try {
						this.executorServices[i].awaitTermination(5000, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						// Do we care?
					}
				}
			}
		}

		this.started = false;
	}
	
	public boolean isStarted(){
		return this.started;
	}

	@SuppressWarnings("unchecked")
	public void load() throws FileNotFoundException {
		XMLObjectReader reader = null;
		try {
			reader = XMLObjectReader.newInstance(new FileInputStream(persistFile.toString()));
			reader.setBinding(binding);

            try {
                this.connectDelay = reader.read(CONNECT_DELAY_PROP, Integer.class);
                this.workerThreads = reader.read(WORKER_THREADS_PROP, Integer.class);
                this.singleThread = reader.read(SINGLE_THREAD_PROP, Boolean.class);
            } catch (java.lang.NullPointerException npe) {
                // ignore.
                // For backward compatibility we can ignore if these values are not defined
            }			


			this.associations = reader.read(ASSOCIATIONS, AssociationMap.class);
			for (FastMap.Entry<String, Association> n = this.associations.head(), end = this.associations.tail(); (n = n.getNext()) != end;) {
				OneToManyAssociationImpl associationTemp = (OneToManyAssociationImpl) n.getValue();
				associationTemp.setManagement(this);
			}

		} catch (XMLStreamException ex) {
			// this.logger.info(
			// "Error while re-creating Linksets from persisted file", ex);
		}
	}

	public void store() {
		try {
			XMLObjectWriter writer = XMLObjectWriter.newInstance(new FileOutputStream(persistFile.toString()));
			writer.setBinding(binding);
			// Enables cross-references.
			// writer.setReferenceResolver(new XMLReferenceResolver());
			writer.setIndentation(TAB_INDENT);

            writer.write(this.connectDelay, CONNECT_DELAY_PROP, Integer.class);
            writer.write(this.workerThreads, WORKER_THREADS_PROP, Integer.class);
            writer.write(this.singleThread, SINGLE_THREAD_PROP, Boolean.class);

			writer.write(this.associations, ASSOCIATIONS, AssociationMap.class);

			writer.close();
		} catch (Exception e) {
			logger.error("Error while persisting the Rule state in file", e);
		}
	}

	public void removeAllResourses() throws Exception {

		synchronized (this) {
			if (!this.started) {
				throw new Exception(String.format("Management=%s not started", this.name));
			}

			if (this.associations.size() == 0)
				// no resources allocated - nothing to do
				return;

			if (logger.isInfoEnabled()) {
				logger.info(String.format("Removing allocated resources: Associations=%d", this.associations.size()));
			}

			synchronized (this) {
				// Remove all associations
				ArrayList<String> lst = new ArrayList<String>();
				for (FastMap.Entry<String, Association> n = this.associations.head(), end = this.associations.tail(); (n = n.getNext()) != end;) {
					lst.add(n.getKey());
				}
				for (String n : lst) {
					this.stopAssociation(n);
					this.removeAssociation(n);
				}

				// We store the cleared state
				this.store();
			}

			for (ManagementEventListener lstr : managementEventListeners) {
				try {
					lstr.onRemoveAllResources();
				} catch (Throwable ee) {
					logger.error("Exception while invoking onRemoveAllResources", ee);
				}
			}
		}
	}


	public OneToManyAssociationImpl addAssociation(String hostAddress, int hostPort, String peerAddress, int peerPort, String assocName) throws Exception {
		return addAssociation(hostAddress, hostPort, peerAddress, peerPort, assocName, IpChannelType.SCTP, null);
	}

	public OneToManyAssociationImpl addAssociation(String hostAddress, int hostPort, String peerAddress, int peerPort, String assocName, IpChannelType ipChannelType,
			String[] extraHostAddresses) throws Exception {

		if (!this.started) {
			throw new Exception(String.format("Management=%s not started", this.name));
		}

		if (hostAddress == null) {
			throw new Exception("Host address cannot be null");
		}

		if (hostPort < 0) {
			throw new Exception("Host port cannot be less than 0");
		}

		if (peerAddress == null) {
			throw new Exception("Peer address cannot be null");
		}

		if (peerPort < 1) {
			throw new Exception("Peer port cannot be less than 1");
		}

		if (assocName == null) {
			throw new Exception("Association name cannot be null");
		}

		synchronized (this) {
			for (FastMap.Entry<String, Association> n = this.associations.head(), end = this.associations.tail(); (n = n.getNext()) != end;) {
				Association associationTemp = n.getValue();

				if (assocName.equals(associationTemp.getName())) {
					throw new Exception(String.format("Already has association=%s", associationTemp.getName()));
				}
/* TODO: We should need a new condition
				if (peerAddress.equals(associationTemp.getPeerAddress()) && associationTemp.getPeerPort() == peerPort) {
					throw new Exception(String.format("Already has association=%s with same peer address=%s and port=%d", associationTemp.getName(),
							peerAddress, peerPort));
				}

				if (hostAddress.equals(associationTemp.getHostAddress()) && associationTemp.getHostPort() == hostPort) {
					throw new Exception(String.format("Already has association=%s with same host address=%s and port=%d", associationTemp.getName(),
							hostAddress, hostPort));
				}
*/
			}

			OneToManyAssociationImpl association = new OneToManyAssociationImpl(hostAddress, hostPort, peerAddress, peerPort, assocName, extraHostAddresses);
			association.setManagement(this);

			AssociationMap<String, Association> newAssociations = new AssociationMap<String, Association>();
			newAssociations.putAll(this.associations);
			newAssociations.put(assocName, association);
			this.associations = newAssociations;
			// associations.put(assocName, association);

			this.store();

			for (ManagementEventListener lstr : managementEventListeners) {
				try {
					lstr.onAssociationAdded(association);
				} catch (Throwable ee) {
					logger.error("Exception while invoking onAssociationAdded", ee);
				}
			}

			if (logger.isInfoEnabled()) {
				logger.info(String.format("Added Associoation=%s of type=%s", association.getName(), association.getAssociationType()));
			}

			return association;
		}
	}

	public Association getAssociation(String assocName) throws Exception {
		if (assocName == null) {
			throw new Exception("Association name cannot be null");
		}
		Association associationTemp = this.associations.get(assocName);

		if (associationTemp == null) {
			throw new Exception(String.format("No Association found for name=%s", assocName));
		}
		return associationTemp;
	}

	/**
	 * @return the associations
	 */
	public Map<String, Association> getAssociations() {
		Map<String, Association> routeTmp = new HashMap<String, Association>();
		routeTmp.putAll(this.associations);
		return routeTmp;
	}

	public void startAssociation(String assocName) throws Exception {
		if (!this.started) {
			throw new Exception(String.format("Management=%s not started", this.name));
		}

		if (assocName == null) {
			throw new Exception("Association name cannot be null");
		}

		Association associationTemp = this.associations.get(assocName);

		if (associationTemp == null) {
			throw new Exception(String.format("No Association found for name=%s", assocName));
		}

		if (associationTemp.isStarted()) {
			throw new Exception(String.format("Association=%s is already started", assocName));
		}

		((OneToManyAssociationImpl) associationTemp).start();
		this.store();
	}

	public void stopAssociation(String assocName) throws Exception {
		if (!this.started) {
			throw new Exception(String.format("Management=%s not started", this.name));
		}

		if (assocName == null) {
			throw new Exception("Association name cannot be null");
		}

		Association association = this.associations.get(assocName);

		if (association == null) {
			throw new Exception(String.format("No Association found for name=%s", assocName));
		}

		((OneToManyAssociationImpl) association).stop();
		this.store();
	}

	public void removeAssociation(String assocName) throws Exception {
		if (!this.started) {
			throw new Exception(String.format("Management=%s not started", this.name));
		}

		if (assocName == null) {
			throw new Exception("Association name cannot be null");
		}

		synchronized (this) {
			Association association = this.associations.get(assocName);

			if (association == null) {
				throw new Exception(String.format("No Association found for name=%s", assocName));
			}

			if (association.isStarted()) {
				throw new Exception(String.format("Association name=%s is started. Stop before removing", assocName));
			}

			AssociationMap<String, Association> newAssociations = new AssociationMap<String, Association>();
			newAssociations.putAll(this.associations);
			newAssociations.remove(assocName);
			this.associations = newAssociations;
			// this.associations.remove(assocName);

			this.store();

			for (ManagementEventListener lstr : managementEventListeners) {
				try {
					lstr.onAssociationRemoved(association);
				} catch (Throwable ee) {
					logger.error("Exception while invoking onAssociationRemoved", ee);
				}
			}
		}
	}

	/**
	 * @return the pendingChanges
	 */
	protected FastList<MultiChangeRequest> getPendingChanges() {
		return pendingChanges;
	}

	/**
	 * @return the socketSelector
	 */
	protected Selector getSocketSelector() {
		return socketSelector;
	}

	protected void populateWorkerThread(int workerThreadTable[]) {
		for (int count = 0; count < workerThreadTable.length; count++) {
			if (this.workerThreadCount == this.workerThreads) {
				this.workerThreadCount = 0;
			}

			workerThreadTable[count] = this.workerThreadCount;
			this.workerThreadCount++;
		}
	}

	protected ExecutorService getExecutorService(int index) {
		return this.executorServices[index];
	}
	
	/*unimplemented management methods*/
	@Override
	public Server addServer(String serverName, String hostAddress, int port)
			throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");	
	}
	
	@Override
	public Server addServer(String serverName, String hostAddress, int port,
			IpChannelType ipChannelType, boolean acceptAnonymousConnections,
			int maxConcurrentConnectionsCount, String[] extraHostAddresses)
			throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");
	}
	@Override
	public Server addServer(String serverName, String hostAddress, int port,
			IpChannelType ipChannelType, String[] extraHostAddresses)
			throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");
	}
	@Override
	public Association addServerAssociation(String peerAddress, int peerPort,
			String serverName, String assocName) throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");
	}
	@Override
	public Association addServerAssociation(String peerAddress, int peerPort,
			String serverName, String assocName, IpChannelType ipChannelType)
			throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not support server type associations!");
	}
	@Override
	public ServerListener getServerListener() {
		return null;
	}
	@Override
	public List<Server> getServers() {
		return Collections.emptyList();
	}
	@Override
	public void removeServer(String serverName) throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");		
	}
	@Override
	public void setServerListener(ServerListener serverListener) {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");		
	}
	@Override
	public void startServer(String serverName) throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");
	}
	@Override
	public void stopServer(String serverName) throws Exception {
		throw new UnsupportedOperationException(MultiManagementImpl.class.getName()+" does not implement server functionality!");
	}	
}