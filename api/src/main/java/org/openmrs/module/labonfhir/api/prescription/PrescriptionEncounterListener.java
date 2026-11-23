package org.openmrs.module.labonfhir.api.prescription;

import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Order;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Daemon;
import org.openmrs.event.EventListener;
import org.openmrs.module.DaemonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sends Bonolo prescriptions when an encounter is created or updated. Besides the encounter's own drug orders, an
 * order that revises or discontinues an earlier one also re-sends the earlier prescription, so the HAPI server
 * shows it as cancelled.
 */
@Component("prescriptionEncounterListener")
public class PrescriptionEncounterListener implements EventListener {

	private static final Logger log = LoggerFactory.getLogger(PrescriptionEncounterListener.class);

	@Autowired
	private EncounterService encounterService;

	@Autowired
	private PrescriptionSender sender;

	private DaemonToken daemonToken;

	public void setDaemonToken(DaemonToken daemonToken) {
		this.daemonToken = daemonToken;
	}

	@Override
	public void onMessage(Message message) {
		Daemon.runInDaemonThread(() -> {
			try {
				processMessage(message);
			}
			catch (Exception e) {
				log.error("Failed to process encounter event for Bonolo prescriptions", e);
			}
		}, daemonToken);
	}

	void processMessage(Message message) {
		if (!(message instanceof MapMessage)) {
			return;
		}
		String uuid;
		try {
			uuid = ((MapMessage) message).getString("uuid");
		}
		catch (JMSException e) {
			log.error("Could not read the encounter uuid of an event", e);
			return;
		}
		if (StringUtils.isBlank(uuid)) {
			return;
		}
		Encounter encounter = encounterService.getEncounterByUuid(uuid);
		if (encounter == null) {
			return;
		}
		encountersToSend(encounter).values().forEach(sender::send);
	}

	/** The encounter itself (if it has drug orders) and the encounters of orders it revises or discontinues. */
	static Map<String, Encounter> encountersToSend(Encounter encounter) {
		Map<String, Encounter> toSend = new LinkedHashMap<>();
		boolean hasDrugOrders = false;
		for (Order order : encounter.getOrders()) {
			if (!(order instanceof DrugOrder) || order.getVoided()) {
				continue;
			}
			hasDrugOrders = true;
			Order previous = order.getPreviousOrder();
			if ((order.getAction() == Order.Action.DISCONTINUE || order.getAction() == Order.Action.REVISE)
			        && previous != null && previous.getEncounter() != null) {
				toSend.put(previous.getEncounter().getUuid(), previous.getEncounter());
			}
		}
		if (hasDrugOrders) {
			toSend.put(encounter.getUuid(), encounter);
		}
		return toSend;
	}
}
