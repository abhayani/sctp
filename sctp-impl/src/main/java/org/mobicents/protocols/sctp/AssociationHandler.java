/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual
 * contributors as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a full listing
 * of individual contributors.
 * 
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License, v. 2.0.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License,
 * v. 2.0 along with this distribution; if not, write to the Free 
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */
package org.mobicents.protocols.sctp;

import org.apache.log4j.Logger;

import com.sun.nio.sctp.AbstractNotificationHandler;
import com.sun.nio.sctp.AssociationChangeNotification;
import com.sun.nio.sctp.HandlerResult;
import com.sun.nio.sctp.SendFailedNotification;
import com.sun.nio.sctp.ShutdownNotification;

/**
 * // TODO Override all methods? // TODO Add Association name for logging
 * 
 * @author amit bhayani
 * 
 */
class AssociationHandler extends AbstractNotificationHandler<AssociationImpl> {

	private static final Logger logger = Logger.getLogger(AssociationHandler.class);

	/**
	 * @param asscoitaion
	 */
	public AssociationHandler() {

	}

	@Override
	public HandlerResult handleNotification(AssociationChangeNotification not, AssociationImpl asscoitaion) {
		switch (not.event()) {
		case COMM_UP:
			int outbound = not.association().maxOutboundStreams();
			int inbound = not.association().maxInboundStreams();

			if (logger.isInfoEnabled()) {
				logger.info(String.format("New association setup for Association=%s with %d outbound streams, and %d inbound streams.\n",
						asscoitaion.getName(), outbound, inbound));
			}

			// Recreate SLS table. Minimum of two is correct?
			asscoitaion.createSLSTable(Math.min(inbound, outbound) - 1);

			asscoitaion.createworkerThreadTable(Math.max(inbound, outbound));

			// TODO assign Thread's ?
			asscoitaion.getAssociationListener().onCommunicationUp(asscoitaion);
			return HandlerResult.CONTINUE;

		case CANT_START:
			logger.error(String.format("Can't start for Association=%s", asscoitaion.getName()));
			return HandlerResult.CONTINUE;
		case COMM_LOST:
			logger.warn(String.format("Communication lost for Association=%s", asscoitaion.getName()));
			
			//Close the Socket
			asscoitaion.close();
			
			if (asscoitaion.getType() == AssociationType.CLIENT) {
				// If Associtaion is of Client type, reinitiate the connection
				// prosedure
				asscoitaion.scheduleConnect();
			}

			asscoitaion.getAssociationListener().onCommunicationLost(asscoitaion);
			return HandlerResult.RETURN;
		case RESTART:
			logger.warn(String.format("Restart for Association=%s", asscoitaion.getName()));
			asscoitaion.getAssociationListener().onCommunicationRestart(asscoitaion);
			return HandlerResult.CONTINUE;
		case SHUTDOWN:
			if (logger.isInfoEnabled()) {
				logger.info(String.format("Shutdown for Association=%s", asscoitaion.getName()));
			}
			asscoitaion.getAssociationListener().onCommunicationShutdown(asscoitaion);
			return HandlerResult.RETURN;
		}

		return HandlerResult.CONTINUE;
	}

	@Override
	public HandlerResult handleNotification(ShutdownNotification not, AssociationImpl asscoitaion) {
		if (logger.isInfoEnabled()) {
			logger.info(String.format("Association=%s SHUTDOWN", asscoitaion.getName()));
		}

		// TODO assign Thread's ?

		asscoitaion.getAssociationListener().onCommunicationShutdown(asscoitaion);

		return HandlerResult.RETURN;
	}

	@Override
	public HandlerResult handleNotification(SendFailedNotification notification, AssociationImpl asscoitaion) {
		logger.error(String.format("Association=%s SendFailedNotification", asscoitaion.getName()));
		return HandlerResult.RETURN;
	}
}