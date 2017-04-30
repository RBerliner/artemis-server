package nz.co.fortytwo.signalk.artemis.intercept;

import static nz.co.fortytwo.signalk.util.SignalKConstants.CONFIG;
import static nz.co.fortytwo.signalk.util.SignalKConstants.CONTEXT;
import static nz.co.fortytwo.signalk.util.SignalKConstants.SUBSCRIBE;
import static nz.co.fortytwo.signalk.util.SignalKConstants.resources;
import static nz.co.fortytwo.signalk.util.SignalKConstants.sources;
import static nz.co.fortytwo.signalk.util.SignalKConstants.vessels;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.Interceptor;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.core.protocol.core.Packet;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionSendMessage;
import org.apache.activemq.artemis.core.server.ServerMessage;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mjson.Json;
import nz.co.fortytwo.signalk.artemis.util.Config;
import nz.co.fortytwo.signalk.artemis.util.Util;

/**
 * Remove rubbish messages, add some headers to categorize message
 * 
 * @author robert
 *
 */
public class GarbageInterceptor implements Interceptor {

	private static Logger logger = LogManager.getLogger(GarbageInterceptor.class);

	@Override
	public boolean intercept(Packet packet, RemotingConnection connection) throws ActiveMQException {
		if (packet instanceof SessionSendMessage) {
			SessionSendMessage realPacket = (SessionSendMessage) packet;

			Message msg = realPacket.getMessage();

			String msgType = getContentType(msg);
			if (logger.isDebugEnabled())
				logger.debug("Msg type is:" + msgType);

			if (msgType != null) {
				msg.putStringProperty(Config.AMQ_CONTENT_TYPE, msgType);
			} else {
				// msg.getBodyBuffer().writeString("{}");
			}

			return true;
		}
		return true;
	}

	public String getContentType(Message message) {
		if (logger.isDebugEnabled()) {
			logger.debug("Msg class: " + message.getClass());
		}

		String msg = Util.readBodyBufferToString(message);

		if (logger.isDebugEnabled()) {
			logger.debug("Msg class: " + message.getClass() + ", body is:" + msg);
		}

		if (msg != null) {
			msg = msg.trim();
			// stomp messages are prefixed with 'ascii:'
			if (msg.startsWith("ascii:")) {
				return Config.STOMP;
			}
			msg = StringUtils.chomp(msg);
			if (msg.startsWith("!AIVDM")) {
				// AIS
				// !AIVDM,1,1,,B,15MwkRUOidG?GElEa<iQk1JV06Jd,0*6D
				return Config.AIS;
			} else if (msg.startsWith("$")) {
				return Config._0183;
			} else if (msg.startsWith("{") && msg.endsWith("}")) {
				Json node = Json.read(msg);
				// avoid full signalk syntax
				if (node.has(vessels) || node.has(CONFIG) || node.has(sources) || node.has(resources))
					return Config.JSON_FULL;
				if (node.has(CONTEXT) && (node.has(SUBSCRIBE)))
					return Config.JSON_SUBSCRIBE;
				if (node.has(CONTEXT))
					return Config.JSON_DELTA;
			}
		}

		return null;
	}

}
