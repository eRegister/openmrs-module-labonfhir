package org.openmrs.module.labonfhir.api.prescription;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Encounter;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.model.FailedTask;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Sends one Bonolo prescription (the drug orders of one encounter) to the FHIR server at labonfhir.lisUrl as a
 * transaction Bundle. A failed send is recorded as a FailedTask of type PRESCRIPTION, keyed by encounter uuid, and
 * retried by RetryFailedTasks.
 */
@Component
public class PrescriptionSender {

	private static final Logger log = LoggerFactory.getLogger(PrescriptionSender.class);

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	@Qualifier("fhirR4")
	private FhirContext ctx;

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	private PrescriptionBundleBuilder builder;

	@Autowired
	private LabOnFhirService labOnFhirService;

	/**
	 * Builds and sends the prescription of an encounter. Failures are recorded for retry, not thrown.
	 */
	public void send(Encounter encounter) {
		if (!config.isPrescriptionPushActive()) {
			return;
		}
		try {
			sendOrThrow(encounter);
		}
		catch (Exception e) {
			log.error("Failed to send Bonolo prescription of encounter {}", encounter.getUuid(), e);
			FailedTask failed = new FailedTask();
			failed.setTaskType(FailedTask.TYPE_PRESCRIPTION);
			failed.setTaskUuid(encounter.getUuid());
			failed.setError(StringUtils.abbreviate(String.valueOf(e.getMessage()), 255));
			failed.setIsSent(false);
			labOnFhirService.saveOrUpdateFailedTask(failed);
		}
	}

	/**
	 * Builds and sends the prescription of an encounter.
	 *
	 * @return true if a bundle was sent, false if the encounter has nothing to send
	 */
	public boolean sendOrThrow(Encounter encounter) {
		PrescriptionBundleBuilder.Result result = builder.build(encounter);
		if (!result.hasBundle()) {
			log.info("Bonolo prescription of encounter {} not sent: {}", encounter.getUuid(), result.getSkipReason());
			return false;
		}
		client.transaction().withBundle(result.getBundle()).execute();
		log.info("Sent Bonolo prescription of encounter {}", encounter.getUuid());
		if (log.isDebugEnabled()) {
			log.debug(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(result.getBundle()));
		}
		return true;
	}
}
