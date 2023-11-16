package org.openmrs.module.labonfhir.api.scheduler;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import lombok.AccessLevel;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.checkerframework.common.returnsreceiver.qual.This;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.OnDelete;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.codesystems.TaskStatus;
import org.openmrs.api.ConceptService;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirDiagnosticReportService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.dao.FhirObservationDao;
import org.openmrs.module.fhir2.api.translators.ObservationReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.ObservationValueTranslator;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.model.TaskRequest;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
@Setter(AccessLevel.PACKAGE)
public class FetchTaskUpdates extends AbstractTask implements ApplicationContextAware {

	private static Log log = LogFactory.getLog(FetchTaskUpdates.class);

	private static ApplicationContext applicationContext;

	private static String LOINC_SYSTEM = "http://loinc.org";

	private static String DISA_LS_SYSTEM = "http://health.gov.ls/laboratory-services/";

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	private FhirTaskService taskService;

	@Autowired
	private FhirDiagnosticReportService diagnosticReportService;

	@Autowired
	FhirObservationDao observationDao;

	@Autowired
	FhirObservationService observationService;

	@Autowired
	ObservationReferenceTranslator observationReferenceTranslator;

	@Autowired
	ObservationValueTranslator observationValueTranslator;

	@Autowired
	@Qualifier("sessionFactory")
	SessionFactory sessionFactory;

	@Autowired
    private LabOnFhirService labOnFhirService;

	/* This is the original code */
	// @Override
	// public void execute() {

	// 	try {
	// 		applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
	// 	} catch (Exception e) {
	// 		// return;
	// 	}

	// 	if (!config.isLisEnabled()) {
	// 		return;
	// 	}

	// 	try {
	// 		// Get List of Tasks that belong to this instance and update them
	// 		updateTasksInBundle(client.search().forResource(Task.class)
	// 				.where(Task.IDENTIFIER.hasSystemWithAnyCode(FhirConstants.OPENMRS_FHIR_EXT_TASK_IDENTIFIER))
	// 				.where(Task.STATUS.exactly().code(TaskStatus.COMPLETED.toCode())).returnBundle(Bundle.class)
	// 				.execute());
	// 	} catch (Exception e) {
	// 		log.error("ERROR executing FetchTaskUpdates : " + e.toString() + getStackTrace(e));
	// 	}

	// 	super.startExecuting();
	// }

	@Override
	public void execute() {

		try {
			applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
		} catch (Exception e) {
			// return;
		}

		if (!config.isLisEnabled()) {
			return;
		}

		try {
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			//dateFormat.setTimeZone(TimeZone.getTimeZone("UTC")); //our hapi fhir uses this timezone
			Date newDate = new Date();
			Calendar calendar = Calendar.getInstance();
			calendar.setTime(newDate);
			calendar.add(Calendar.YEAR, -5);
			Date fiveYearsAgo = calendar.getTime();

			// TaskRequest lastRequest = labOnFhirService.getLastTaskRequest();
			// String lastRequestDate = dateFormat.format(fiveYearsAgo);
			// if (lastRequest != null) {
			// 	lastRequestDate = dateFormat.format(lastRequest.getRequestDate());
			// }

			// String currentTime = dateFormat.format(newDate);

			// Translate dates (lasstreq date & date now) to UTC since HAPI is running on UTC TZ
			TaskRequest lastRequest = labOnFhirService.getLastTaskRequest();
			String lastRequestDate = dateFormat.format(convertDateToUTC(fiveYearsAgo, TimeZone.getDefault()));
			if (lastRequest != null) {
				lastRequestDate = dateFormat.format(convertDateToUTC(lastRequest.getRequestDate(), TimeZone.getDefault()));
			}
			Date newDateUTC = convertDateToUTC(newDate, TimeZone.getDefault());
			String currentTime = dateFormat.format(newDateUTC);

			DateRangeParam lastUpdated = new DateRangeParam().setLowerBound(lastRequestDate).setUpperBound(currentTime);
			
			// Get List of Tasks that belong to this instance and update them
			//The request below will fetch the first page
			Bundle taskBundle = client.search().forResource(Task.class)
			        .where(Task.IDENTIFIER.hasSystemWithAnyCode(FhirConstants.OPENMRS_FHIR_EXT_TASK_IDENTIFIER))
			        .where(Task.STATUS.exactly().code(TaskStatus.COMPLETED.toCode())).lastUpdated(lastUpdated)
			        .returnBundle(Bundle.class).execute();

			log.warn("Just ran query with lastupdated range - Lower bound: "+lastUpdated.getLowerBoundAsInstant()+ " and Upper bound: "+lastUpdated.getUpperBoundAsInstant());		
			
			List<Bundle> taskBundles = new ArrayList<>();
			taskBundles.add(taskBundle);
			//Support FHIR Server Pagination -- fetch succeeding pages
			while (taskBundle.getLink(IBaseBundle.LINK_NEXT) != null) {
				// Load the next page
				taskBundle = client.loadPage().next(taskBundle).execute();
				taskBundles.add(taskBundle);
			}		
			updateTasksInBundle(taskBundles);
			
			TaskRequest request = new TaskRequest();
			request.setRequestDate(newDate);
			labOnFhirService.saveOrUpdateTaskRequest(request);
			
		} catch (Exception e) {
			log.error("ERROR executing FetchTaskUpdates : " + e.toString() + getStackTrace(e));
		}

		super.startExecuting();
	}

	@Override
	public void shutdown() {
		log.debug("shutting down FetchTaskUpdates Task");

		this.stopExecuting();
	}

	private void updateTasksInBundle(List<Bundle> taskBundles) {
		for (Bundle bundle : taskBundles) {
			for (Iterator tasks = bundle.getEntry().iterator(); tasks.hasNext();) {
				String openmrsTaskUuid = null;
				
				try {
					// Read incoming LIS Task
					Task openelisTask = (Task) ((Bundle.BundleEntryComponent) tasks.next()).getResource();
					openmrsTaskUuid = openelisTask.getIdentifierFirstRep().getValue();
					// Find original openmrs task using Identifier
					Task openmrsTask = taskService.get(openmrsTaskUuid);
					
					// Only update if matching OpenMRS Task found
					if (openmrsTask != null) {
						// Handle status
						openmrsTask.setStatus(openelisTask.getStatus());
						
						Boolean taskOutPutUpdated = false;
						if (openelisTask.hasOutput()) {
							// openmrsTask.setOutput(openelisTask.getOutput());
							taskOutPutUpdated = updateOutput(openelisTask.getOutput(), openmrsTask);
						}
						if (taskOutPutUpdated) {
							taskService.update(openmrsTaskUuid, openmrsTask);
						}
					}
				}
				catch (Exception e) {
					log.error("Could not save task " + openmrsTaskUuid + ":" + e.toString() + getStackTrace(e));
				}
			}
		}
		
	}

	private Boolean updateOutput(List<Task.TaskOutputComponent> output, Task openmrsTask) {

		Reference encounterReference = openmrsTask.getEncounter();
		List<Reference> basedOn = openmrsTask.getBasedOn();
		List<String> allExistingLoincCodes = new ArrayList<>();
		//allExistingLoincCodes list contains loinc codes of DiagReports already in OpenMRS (i.e. processed)
		Boolean taskOutPutUpdated = false;
		// openmrsTask.getOutput().stream().map(ouput -> ouput.getType().getCoding());
		openmrsTask.getOutput().forEach(out -> {
			out.getType().getCoding().stream().filter(coding -> coding.hasSystem())
					.filter(coding -> coding.getSystem().equals(LOINC_SYSTEM))
					.forEach(coding -> {
						allExistingLoincCodes.add(coding.getCode());
					});
		});

		if (!output.isEmpty()) {
						
			log.info("Got a task "+openmrsTask.getId());
			// Save each output entry
			for (Iterator outputRefI = output.stream().iterator(); outputRefI.hasNext();) {
				Task.TaskOutputComponent outputRef = (Task.TaskOutputComponent) outputRefI.next();
				String openelisDiagnosticReportUuid = ((Reference) outputRef.getValue()).getReferenceElement()
						.getIdPart();
				// Get Diagnostic Report and associated Observations (using include)
				Bundle diagnosticReportBundle = client.search().forResource(DiagnosticReport.class)
						.where(new TokenClientParam("_id").exactly().code(openelisDiagnosticReportUuid))
						.include(DiagnosticReport.INCLUDE_RESULT).include(DiagnosticReport.INCLUDE_SUBJECT)
						.returnBundle(Bundle.class).execute();

				DiagnosticReport diagnosticReport = (DiagnosticReport) diagnosticReportBundle.getEntryFirstRep()
						.getResource();
				Coding diagnosticReportCode = diagnosticReport.getCode().getCodingFirstRep();
				if (diagnosticReportCode.getSystem().equals(LOINC_SYSTEM)) {
					List<Reference> results = new ArrayList<>();
					
					String labTestType = "";
					//log.warn("DIagnostic Report code is "+ diagnosticReportCode.getCode());
					FhirContext ctx = FhirContext.forR4();
					if (!allExistingLoincCodes.contains(diagnosticReportCode.getCode())) {
						if(diagnosticReportCode.getCode().equals("20447-9")){
							log.warn("Incoming task holds results for VL");
							labTestType = "VL";
						}
						else{
							log.warn("Did not register lab test type as VL");
						}
						// save Observation
						for (Bundle.BundleEntryComponent entry : diagnosticReportBundle.getEntry()) {
							if (entry.hasResource()) {
								if (ResourceType.Observation.equals(entry.getResource().getResourceType())) {
									Observation newObs = (Observation) entry.getResource();
									newObs.setEncounter(encounterReference);
									newObs.setBasedOn(basedOn);
									//Observation fhirObs = newObs;
									newObs = observationService.create(newObs);
									Reference obsRef = new Reference();
									obsRef.setReference(
											ResourceType.Observation + "/" + newObs.getIdElement().getIdPart());
									results.add(obsRef);

									// for VL add additional Obs
									// if(labTestType.equals("VL")){
									// 	log.warn("Found a VL Result so ... mapping results to existing HIVTC concepts");
									// 	//apply VL rules ... i.e. map to existing concepts
									// 	List<Reference> additionalResults = saveSecondaryVLObs(fhirObs);
									// 	results.addAll(additionalResults);
									// }
								}
							}
						}
						diagnosticReport.setResult(results);
						diagnosticReport.setEncounter(encounterReference);
						diagnosticReport = diagnosticReportService.create(diagnosticReport);
						openmrsTask.addOutput().setValue(
								new Reference().setType(FhirConstants.DIAGNOSTIC_REPORT)
										.setReference(diagnosticReport.getIdElement().getIdPart()))
								.setType(diagnosticReport.getCode());
						taskOutPutUpdated = true;
					}
				}
			}
		}
		return taskOutPutUpdated;
	}

	private static Boolean containsVLValueCodes(List<Coding> list) {
		List <String> VLValueCodes = new ArrayList<String>();
		VLValueCodes.add("HIVVL-HIVVM");
		VLValueCodes.add("HIVVL-HIVVT");
		VLValueCodes.add("70241-5");
        for (Coding item : list) {
            if (VLValueCodes.contains(item.getCode())) {
                return true;
            }
        }
        return false;
    }

	private static Boolean containsVLLowValueCodes(List<Coding> list) {
		List <String> VLLowValueCodes = new ArrayList<String>();
		VLLowValueCodes.add("HIVVL-HIVVC");
		VLLowValueCodes.add("HIVVL-HIVVH");
        for (Coding item : list) {
            if (VLLowValueCodes.contains(item.getCode())) {
                return true;
            }
        }
        return false;
    }

	// This function maps incoming VL results to existing Obs conecpts to avoid fiddling with the xds-sender module
	private List<Reference> saveSecondaryVLObs(Observation newObs){
			List<Reference> results = new ArrayList<>();
			//FhirContext ctx = FhirContext.forR4();

			Observation HIVTCVLResultReturnDate = copyObs(newObs);					
			HIVTCVLResultReturnDate.setCode(new CodeableConcept().addCoding(getDISACodingFor("Viral load blood results return date", "HIVTCVLReturnDate")));
			HIVTCVLResultReturnDate.setValue(new DateTimeType(new Date())); //today's date as we read results from HAPI FHIR server

			if(containsVLValueCodes(newObs.getCode().getCoding())){
				// Observation HIVTCVLDataObs = copyObs(newObs);
				// HIVTCVLDataObs.setCode(new CodeableConcept().addCoding(getDISACodingFor("HIVTC, Viral Load Data", "HIVTCVLDATA")));
				Observation HIVTCVLDataAbnormalObs = copyObs(newObs);
				HIVTCVLDataAbnormalObs.setCode(new CodeableConcept().addCoding(getDISACodingFor("Viral Load Abnormal", "HIVTCVLDATAAbnormal")));
				Observation HIVTCVLResult = copyObs(newObs);					
				HIVTCVLResult.setCode(new CodeableConcept().addCoding(getDISACodingFor("Viral Load Result", "HIVTCVLResult")));
				Observation HIVTCVL = newObs;
				HIVTCVL.setCode(new CodeableConcept().addCoding(getDISACodingFor("HIVTC, Viral Load", "HIVTCVL")));
				
				//Internal Rules
				BigDecimal VLValueNormalCutoff = new BigDecimal(999);
				BigDecimal VLValueLow1 = new BigDecimal(20);
				BigDecimal VLValueLow2 = new BigDecimal(30);
				BigDecimal reportedVL = newObs.getValueQuantity().getValue();
				
				if(reportedVL.compareTo(VLValueNormalCutoff) > 0){
					//value is greater than 999 so it is Abnormal
					// Set HIVTC, VL Data - Abonormal to true
					HIVTCVLDataAbnormalObs.setValue(new BooleanType(true));
				}
				else{ 
					// VL value is less or equal to 999
					HIVTCVLDataAbnormalObs.setValue(new BooleanType(false));
					//if(val is < 20 or <30)
					if((reportedVL.compareTo(VLValueLow1) == 0) && (newObs.getValueQuantity().hasComparator())){
						if(newObs.getValueQuantity().getComparator().toCode().equals("<")){
							//set HIVTC, Viral Load Result to <20
							CodeableConcept lessThan20 = new CodeableConcept(getDISACodingFor("Less than 20 copies/ml", "<20"));
							HIVTCVLResult.setValue(lessThan20);
						}	
					}
					else if ((reportedVL.compareTo(VLValueLow2) == 0) && (newObs.getValueQuantity().hasComparator())){
						if(newObs.getValueQuantity().getComparator().toCode().equals("<")){
							//set HIVTC, Viral Load Result to <30
						}	
					}
					else if(reportedVL.compareTo(VLValueLow1) >= 0){
						//set HIVTC, Viral Load Result to >=20
						CodeableConcept greaterOrEq20 = new CodeableConcept(getDISACodingFor("Greater or Equal to 20 copies/ml", ">=20"));
						HIVTCVLResult.setValue(greaterOrEq20);
					}
					else if(reportedVL.compareTo(VLValueLow2) >= 0){
						//set HIVTC, Viral Load Result to >=30
					}
				}
				
				//save VL abnormal obs
				HIVTCVLDataAbnormalObs = observationService.create(HIVTCVLDataAbnormalObs);
				//save VL result (<20, >=20, etc.)
				HIVTCVLResult = observationService.create(HIVTCVLResult);
				//save VL value (e.g. 500 copies/ml)
				HIVTCVL = observationService.create(HIVTCVL);
				//save VL result reception date (today)
				observationService.create(HIVTCVLResultReturnDate);
				
				/* TDO - HIVTC VL Data Obs (members - Data & Abnormal obs) - transient state issues
				List<Reference> VLObsReferences = new ArrayList<Reference>();
				Reference obsVLRef = new Reference();
				obsVLRef.setReference(ResourceType.Observation + "/" + newObs.getIdElement().getIdPart()).setType("Observation");
				VLObsReferences.add(obsVLRef);
				Reference obsVLRefAbnormal = new Reference();
				obsVLRefAbnormal.setReference(ResourceType.Observation + "/" + HIVTCVLDataAbnormalObs.getIdElement().getIdPart()).setType("Observation");
				VLObsReferences.add(obsVLRefAbnormal);
				HIVTCVLDataObs.setHasMember(VLObsReferences);
				log.warn("-------------VL DATA Obs----------------------------------------------");
				log.warn("Data Obs"+ctx.newJsonParser().encodeResourceToString(HIVTCVLDataObs));
				
				//StringType value = (StringType) (newObs.getValue().toString()+", "+HIVTCVLDataAbnormalObs.getValue().toString());
				//StringType val = new StringType(newObs.getValue().toString()+", "+HIVTCVLDataAbnormalObs.getValue());
				StringType val = new StringType("sample val");
				//Type type = new StringType();
				HIVTCVLDataObs.setValue(val);
				//observationService.create(HIVTCVLDataObs);
				*/
			}
			else if(containsVLLowValueCodes(newObs.getCode().getCoding())){
				Observation HIVTCVLResult = copyObs(newObs);		
				HIVTCVLResult.setCode(new CodeableConcept().addCoding(getDISACodingFor("Viral Load Result", "HIVTCVLResult")));
				CodeableConcept undetectable = new CodeableConcept(getDISACodingFor("Undetectable", "Undetectable"));
				HIVTCVLResult.setValue(undetectable);

				//save VL result (in this case undetectable / LDL)
				HIVTCVLResult = observationService.create(HIVTCVLResult);
				//save VL result return date
				HIVTCVLResultReturnDate = observationService.create(HIVTCVLResultReturnDate);
			}
		return results;
	}

	private Observation copyObs(Observation newObs){
		Observation Obs = new Observation();
					Obs.setId(UUID.randomUUID().toString());
					Obs.setEncounter(newObs.getEncounter());
					Obs.setSubject(newObs.getSubject());
					Obs.setBasedOn(newObs.getBasedOn());
					Obs.setStatus(newObs.getStatus());
					Obs.setEffective(newObs.getEffectiveDateTimeType());
		return Obs;
	}

	private Coding getDISACodingFor(String name, String code) {
		String url = "http://health.gov.ls/laboratory-services/";
        return new Coding(url, code, name);
    }

	private static Date convertDateToUTC(Date date, TimeZone fromTimeZone) {
        long timeInMilliseconds = date.getTime();
        long offset = fromTimeZone.getOffset(timeInMilliseconds);
        return new Date(timeInMilliseconds - offset);
    }

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
}
