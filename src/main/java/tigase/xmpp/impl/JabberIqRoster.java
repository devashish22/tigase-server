/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.xmpp.impl;

import java.util.List;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Queue;
import java.util.Map;
import java.util.logging.Logger;
import tigase.server.Packet;
import tigase.util.JIDUtils;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.StanzaType;
import tigase.xmpp.XMPPProcessor;
import tigase.xmpp.XMPPProcessorIfc;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.XMPPException;
import tigase.db.NonAuthUserRepository;

import static tigase.xmpp.impl.Roster.SubscriptionType;

/**
 * Class <code>JabberIqRoster</code> implements part of <em>RFC-3921</em> -
 * <em>XMPP Instant Messaging</em> specification describing roster management.
 * 7.  Roster Management
 *
 *
 * Created: Tue Feb 21 17:42:53 2006
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public abstract class JabberIqRoster {

  /**
   * Private logger for class instancess.
   */
  private static Logger log =
		Logger.getLogger("tigase.xmpp.impl.JabberIqRoster");

  protected static final String XMLNS = "jabber:iq:roster";
	private static final String[] ELEMENTS = {"query"};
  private static final String[] XMLNSS = {XMLNS};
  protected static final Element[] DISCO_FEATURES =	{
		new Element("feature", new String[] {"var"}, new String[] {XMLNS})
	};

	private static void processSetRequest(final Packet packet,
		final XMPPResourceConnection session,	final Queue<Packet> results)
    throws NotAuthorizedException {

		Element request = packet.getElement();

    String buddy =
			JIDUtils.getNodeID(request.getAttribute("/iq/query/item", "jid"));
    Element item =  request.findChild("/iq/query/item");
    String subscription = item.getAttribute("subscription");
    if (subscription != null && subscription.equals("remove")) {
			SubscriptionType sub = Roster.getBuddySubscription(session, buddy);
			if (sub != null && sub != SubscriptionType.none) {
				Element pres = new Element("presence");
				pres.setAttribute("to", buddy);
				pres.setAttribute("from", session.getUserId());
				pres.setAttribute("type", "unsubscribe");
				results.offer(new Packet(pres));
				pres = new Element("presence");
				pres.setAttribute("to", buddy);
				pres.setAttribute("from", session.getUserId());
				pres.setAttribute("type", "unsubscribed");
				results.offer(new Packet(pres));
				pres = new Element("presence");
				pres.setAttribute("to", buddy);
				pres.setAttribute("from", session.getJID());
				pres.setAttribute("type", "unavailable");
				results.offer(new Packet(pres));
			}
			// It happens sometimes that the client still think the buddy
			// is in the roster while he isn't. In such a case just ensure the
			// client that the buddy has been removed for sure
			Element it = new Element("item");
			it.setAttribute("jid", buddy);
			it.setAttribute("subscription", "remove");
			Roster.updateBuddyChange(session, results, it);
			Roster.removeBuddy(session, buddy);
      results.offer(packet.okResult((String)null, 0));
    } else {
      String name = request.getAttribute("/iq/query/item", "name");
      if (name == null) {
        name = buddy;
      } // end of if (name == null)
      Roster.setBuddyName(session, buddy, name);
      if (Roster.getBuddySubscription(session, buddy) == null) {
        Roster.setBuddySubscription(session, SubscriptionType.none, buddy);
      } // end of if (getBuddySubscription(session, buddy) == null)
      List<Element> groups = item.getChildren();
      if (groups != null && groups.size() > 0) {
        String[] gr = new String[groups.size()];
        int cnt = 0;
        for (Element group : groups) {
					gr[cnt++] = (group.getCData() == null ? "" : group.getCData());
        } // end of for (ElementData group : groups)
        session.setDataList(Roster.groupNode(buddy), Roster.GROUPS, gr);
      } else {
				session.removeData(Roster.groupNode(buddy), Roster.GROUPS);
			} // end of else
      results.offer(packet.okResult((String)null, 0));
      Roster.updateBuddyChange(session, results,
				Roster.getBuddyItem(session, buddy));
    } // end of else
  }

	private static void processGetRequest(final Packet packet,
		final XMPPResourceConnection session,	final Queue<Packet> results,
		final Map<String, Object> settings)
    throws NotAuthorizedException {
    String[] buddies = Roster.getBuddies(session);
    if (buddies != null) {
			Element query = new Element("query");
			query.setXMLNS(XMLNS);
      for (String buddy : buddies) {
				query.addChild(Roster.getBuddyItem(session, buddy));
      }
			if (query.getChildren() != null && query.getChildren().size() > 0) {
				results.offer(packet.okResult(query, 0));
			} else {
				results.offer(packet.okResult((String)null, 1));
			} // end of if (buddies != null) else
		} else {
			results.offer(packet.okResult((String)null, 1));
		}
		List<Element> its = DynamicRoster.getRosterItems(session, settings);
		if (its != null) {
			LinkedList<Element> items = new LinkedList<Element>(its);
			while (items.size() > 0) {
				Element iq = new Element("iq",
					new String[] {"type", "id", "to"},
					new String[] {"set", "dr-"+items.size(), session.getJID()});
				Element query = new Element("query");
				query.setXMLNS(XMLNS);
				iq.addChild(query);
				query.addChild(items.poll());
				while (query.getChildren().size() < 20 && items.size() > 0) {
					query.addChild(items.poll());
				}
				Packet rost_res = new Packet(iq);
				rost_res.setTo(session.getConnectionId());
				rost_res.setFrom(packet.getTo());
				results.offer(rost_res);
			}
		}
  }

	public static void process(final Packet packet,
		final XMPPResourceConnection session,
		final NonAuthUserRepository repo, final Queue<Packet> results,
		final Map<String, Object> settings) throws XMPPException {

		try {
			if (packet.getElemFrom() != null
				&& !session.getUserId().equals(JIDUtils.getNodeID(packet.getElemFrom()))) {
				// RFC says: ignore such request
				log.warning(
					"Roster request 'from' attribute doesn't match session userid: "
					+ session.getUserId()
					+ ", request: " + packet.getStringData());
				return;
			} // end of if (packet.getElemFrom() != null
				// && !session.getUserId().equals(JIDUtils.getNodeID(packet.getElemFrom())))

			StanzaType type = packet.getType();
			switch (type) {
			case get:
				processGetRequest(packet, session, results, settings);
				break;
			case set:
        processSetRequest(packet, session, results);
				break;
			case result:
				// Ignore
				break;
			default:
				results.offer(Authorization.BAD_REQUEST.getResponseMessage(packet,
						"Request type is incorrect", false));
				break;
			} // end of switch (type)

		} catch (NotAuthorizedException e) {
      log.warning(
				"Received roster request but user session is not authorized yet: " +
        packet.getStringData());
			results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
					"You must authorize session first.", true));
		} // end of try-catch
	}


} // JabberIqRoster
