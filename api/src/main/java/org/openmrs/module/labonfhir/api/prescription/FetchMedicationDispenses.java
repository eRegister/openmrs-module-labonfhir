package org.openmrs.module.labonfhir.api.prescription;

import java.util.Date;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.DateRangeParam;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.openmrs.Concept;
import org.openmrs.DrugOrder;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.ObsService;
import org.openmrs.api.OrderService;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Scheduler task: reads MedicationDispense resources changed on the FHIR server since the last run and writes them
 * back to the drug orders of Bonolo prescriptions.
 * <ul>
 * <li>completed: saves Bahmni's "Dispensed" obs (true) linked to the order, then re-sends the prescription, which
 * now reports the order as completed;</li>
 * <li>in-progress: logged only (OpenMRS before 2.2 has no fulfiller status to store it);</li>
 * <li>declined, entered-in-error: logged only.</li>
 * </ul>
 */
@Component
public class FetchMedicationDispenses extends AbstractTask implements ApplicationContextAware {

	private static final Logger log = LoggerFactory.getLogger(FetchMedicationDispenses.class);

	private static ApplicationContext applicationContext;

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	private OrderService orderService;

	@Autowired
	private ObsService obsService;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	@Qualifier("adminService")
	private AdministrationService administrationService;

	@Autowired
	private PrescriptionBundleBuilder builder;

	@Autowired
	private PrescriptionSender sender;

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		applicationContext = context;
	}

	@Override
	public void execute() {
		if (applicationContext != null) {
			applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
		}
		if (!config.isPrescriptionPushActive()) {
			return;
		}
		Date to = new Date();
		Date from = lastFetch();
		try {
			Bundle page = client.search().forResource(MedicationDispense.class)
			        .lastUpdated(new DateRangeParam(from, to)).returnBundle(Bundle.class).execute();
			while (page != null) {
				for (Bundle.BundleEntryComponent entry : page.getEntry()) {
					if (entry.getResource() instanceof MedicationDispense) {
						try {
							processDispense((MedicationDispense) entry.getResource());
						}
						catch (Exception e) {
							log.error("Could not process MedicationDispense {}",
							    entry.getResource().getIdElement().getIdPart(), e);
						}
					}
				}
				page = page.getLink(Bundle.LINK_NEXT) != null ? client.loadPage().next(page).execute() : null;
			}
			administrationService.setGlobalProperty(LabOnFhirConfig.GP_LAST_DISPENSE_FETCH,
			    new InstantType(to).getValueAsString());
		}
		catch (Exception e) {
			log.error("Fetching MedicationDispense resources failed; the same window is read again next run", e);
		}
	}

	private Date lastFetch() {
		String value = administrationService.getGlobalProperty(LabOnFhirConfig.GP_LAST_DISPENSE_FETCH);
		if (StringUtils.isBlank(value)) {
			return null;
		}
		try {
			return new InstantType(value.trim()).getValue();
		}
		catch (Exception e) {
			log.warn("Ignoring unreadable {} = '{}'", LabOnFhirConfig.GP_LAST_DISPENSE_FETCH, value);
			return null;
		}
	}

	void processDispense(MedicationDispense dispense) {
		String dispenseId = dispense.getIdElement().getIdPart();
		if (!dispense.hasAuthorizingPrescription()) {
			log.warn("MedicationDispense {} has no authorizingPrescription; ignored", dispenseId);
			return;
		}
		String orderUuid = dispense.getAuthorizingPrescriptionFirstRep().getReferenceElement().getIdPart();
		Order order = orderUuid == null ? null : orderService.getOrderByUuid(orderUuid);
		if (!(order instanceof DrugOrder)) {
			log.warn("MedicationDispense {} points to {}, which is not a drug order here; ignored", dispenseId,
			    orderUuid);
			return;
		}
		MedicationDispense.MedicationDispenseStatus status = dispense.getStatus();
		if (status == MedicationDispense.MedicationDispenseStatus.COMPLETED) {
			Date handedOver = dispense.hasWhenHandedOver() ? dispense.getWhenHandedOver() : new Date();
			if (markDispensed((DrugOrder) order, handedOver)) {
				log.info("Drug order {} marked Dispensed (MedicationDispense {})", orderUuid, dispenseId);
				sender.send(order.getEncounter());
			}
		} else if (status == MedicationDispense.MedicationDispenseStatus.INPROGRESS) {
			log.info("Drug order {} partly dispensed (MedicationDispense {}); not stored: OpenMRS before 2.2 has no "
			        + "fulfiller status", orderUuid, dispenseId);
		} else {
			log.info("MedicationDispense " + dispenseId + " for drug order " + orderUuid + " has status "
			        + (status == null ? "none" : status.toCode()) + "; nothing written");
		}
	}

	/**
	 * Saves Bahmni's Dispensed order attribute: an obs of the "Dispensed" concept, true, linked to the order.
	 *
	 * @return true if it was saved, false if the order was already dispensed or the concept is missing
	 */
	boolean markDispensed(DrugOrder order, Date handedOver) {
		if (builder.isDispensed(order)) {
			return false;
		}
		Concept dispensed = conceptService.getConceptByUuid(BonoloConcepts.DISPENSED);
		Concept trueConcept = conceptService.getTrueConcept();
		if (dispensed == null || trueConcept == null) {
			log.error("Concept 'Dispensed' (" + BonoloConcepts.DISPENSED
			        + ") or the TRUE concept is missing; cannot mark order " + order.getUuid());
			return false;
		}
		Obs obs = new Obs(order.getPatient(), dispensed, handedOver,
		        order.getEncounter() == null ? null : order.getEncounter().getLocation());
		obs.setEncounter(order.getEncounter());
		obs.setOrder(order);
		obs.setValueCoded(trueConcept); // what Obs.setValueBoolean(true) stores for a Boolean concept
		obsService.saveObs(obs, null);
		return true;
	}
}
