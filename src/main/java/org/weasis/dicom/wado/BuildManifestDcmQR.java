/*******************************************************************************
 * Copyright (c) 2014 Weasis Team.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *******************************************************************************/
package org.weasis.dicom.wado;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.service.QueryRetrieveLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.data.Patient;
import org.weasis.dicom.data.SOPInstance;
import org.weasis.dicom.data.Series;
import org.weasis.dicom.data.Study;
import org.weasis.dicom.op.CFind;
import org.weasis.dicom.param.DicomParam;
import org.weasis.dicom.param.DicomState;
import org.weasis.dicom.util.DateUtil;
import org.weasis.dicom.util.StringUtil;
import org.weasis.dicom.wado.WadoQuery.WadoMessage;

public class BuildManifestDcmQR {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildManifestDcmQR.class);

    public static WadoMessage buildFromPatientID(DicomQueryParams params, String patientID) throws Exception {
        if (!StringUtil.hasText(patientID) && params == null) {
            String message = "No Series found with SeriesInstanceUIDs ";

            return new WadoMessage("Missing PatientID", message, WadoMessage.eLevel.WARN);
        }

        int beginIndex = patientID.indexOf("^^^");
        int offset = 3;
        // IssuerOfPatientID filter ( syntax like in HL7 with extension^^^root)
        if (beginIndex == -1) {
            // if patientID has been encrypted
            beginIndex = patientID.indexOf("%5E%5E%5E");
            offset = 9;
        }

        DicomParam[] keysStudies = {
            // Matching Keys
            new DicomParam(Tag.PatientID, beginIndex < 0 ? patientID : patientID.substring(0, beginIndex)),
            // Return Keys, IssuerOfPatientID is a return key except when passed as a extension of PatientID
            new DicomParam(Tag.IssuerOfPatientID, beginIndex < 0 ? null : patientID.substring(beginIndex + offset)),
            new DicomParam(Tag.PatientName, params.getPatientName()),
            new DicomParam(Tag.PatientBirthDate, params.getPatientBirthDate()), CFind.PatientSex,
            CFind.ReferringPhysicianName, CFind.StudyDescription, CFind.StudyDate, CFind.StudyTime,
            CFind.AccessionNumber, CFind.StudyInstanceUID, CFind.StudyID, new DicomParam(Tag.ModalitiesInStudy) };

        DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(), params.getCalledNode(), 0,
            QueryRetrieveLevel.STUDY, keysStudies);

        List<Attributes> studies = state.getDicomRSP();
        if (studies != null) {
            Collections.sort(studies, new Comparator<Attributes>() {

                @Override
                public int compare(Attributes o1, Attributes o2) {
                    Date date1 = o1.getDate(Tag.StudyDate);
                    Date date2 = o2.getDate(Tag.StudyDate);
                    if (date1 != null && date2 != null) {
                        // inverse time
                        int rep = date2.compareTo(date1);
                        if (rep == 0) {
                            Date time1 = o1.getDate(Tag.StudyTime);
                            Date time2 = o2.getDate(Tag.StudyTime);
                            if (time1 != null && time2 != null) {
                                // inverse time
                                return time2.compareTo(time1);
                            }
                        } else {
                            return rep;
                        }
                    } else {
                        if (date1 == null) {
                            return 1;
                        }
                        if (date2 == null) {
                            return -1;
                        }
                    }
                    return 0;
                }
            });

            if (StringUtil.hasText(params.getLowerDateTime())) {
                Date lowerDateTime = null;
                try {
                    lowerDateTime = javax.xml.bind.DatatypeConverter.parseDateTime(params.getLowerDateTime()).getTime();
                } catch (Exception e) {
                    LOGGER.error("Cannot parse date: {}", params.getLowerDateTime());
                }
                if (lowerDateTime != null) {
                    for (int i = studies.size() - 1; i >= 0; i--) {
                        Attributes s = studies.get(i);
                        Date date = DateUtil.dateTime(s.getDate(Tag.StudyDate), s.getDate(Tag.StudyTime));
                        int rep = date.compareTo(lowerDateTime);
                        if (rep > 0) {
                            studies.remove(i);
                        }
                    }
                }
            }

            if (StringUtil.hasText(params.getUpperDateTime())) {
                Date upperDateTime = null;
                try {
                    upperDateTime = javax.xml.bind.DatatypeConverter.parseDateTime(params.getUpperDateTime()).getTime();
                } catch (Exception e) {
                    LOGGER.error("Cannot parse date: {}", params.getUpperDateTime());
                }
                if (upperDateTime != null) {
                    for (int i = studies.size() - 1; i >= 0; i--) {
                        Attributes s = studies.get(i);
                        Date date = DateUtil.dateTime(s.getDate(Tag.StudyDate), s.getDate(Tag.StudyTime));
                        int rep = date.compareTo(upperDateTime);
                        if (rep < 0) {
                            studies.remove(i);
                        }
                    }
                }
            }

            if (StringUtil.hasText(params.getMostRecentResults())) {
                int recent = StringUtil.getInteger(params.getMostRecentResults());
                if (recent > 0) {
                    for (int i = studies.size() - 1; i >= recent; i--) {
                        studies.remove(i);
                    }
                }
            }

            if (StringUtil.hasText(params.getModalitiesInStudy())) {
                for (int i = studies.size() - 1; i >= 0; i--) {
                    Attributes s = studies.get(i);
                    String m = s.getString(Tag.ModalitiesInStudy);
                    if (StringUtil.hasText(m)) {
                        boolean remove = true;
                        for (String mod : params.getModalitiesInStudy().split(",")) {
                            if (m.indexOf(mod) != -1) {
                                remove = false;
                                break;
                            }
                        }

                        if (remove) {
                            studies.remove(i);
                        }
                    }
                }

            }

            if (StringUtil.hasText(params.getKeywords())) {
                String[] keys = params.getKeywords().split(",");
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = StringUtil.deAccent(keys[i].trim().toUpperCase());
                }

                study: for (int i = studies.size() - 1; i >= 0; i--) {
                    Attributes s = studies.get(i);
                    String desc = StringUtil.deAccent(s.getString(Tag.StudyDescription, "").toUpperCase());

                    for (int j = 0; j < keys.length; j++) {
                        if (desc.contains(keys[j])) {
                            continue study;
                        }
                    }
                    studies.remove(i);
                }
            }

            for (Attributes studyDataSet : studies) {
                fillSeries(params, studyDataSet);
            }
        }
        return null;
    }

    public static List<Patient> buildFromStudyInstanceUID(DicomQueryParams params, String studyInstanceUID)
        throws Exception {
        if (!StringUtil.hasText(studyInstanceUID)) {
            return null;
        }
        DicomParam[] keysStudies = {
            // Matching Keys
            new DicomParam(Tag.StudyInstanceUID, studyInstanceUID),
            // Return Keys
            CFind.PatientID, CFind.IssuerOfPatientID, CFind.PatientName, CFind.PatientBirthDate, CFind.PatientSex,
            CFind.ReferringPhysicianName, CFind.StudyDescription, CFind.StudyDate, CFind.StudyTime,
            CFind.AccessionNumber, CFind.StudyID };

        fillStudy(params, keysStudies);

        return params.getPatients();
    }

    public static List<Patient> buildFromStudyAccessionNumber(DicomQueryParams params, String accessionNumber)
        throws Exception {
        if (!StringUtil.hasText(accessionNumber)) {
            return null;
        }
        DicomParam[] keysStudies = {
            // Matching Keys
            new DicomParam(Tag.AccessionNumber, accessionNumber),
            // Return Keys
            CFind.PatientID, CFind.IssuerOfPatientID, CFind.PatientName, CFind.PatientBirthDate, CFind.PatientSex,
            CFind.ReferringPhysicianName, CFind.StudyDescription, CFind.StudyDate, CFind.StudyTime,
            CFind.StudyInstanceUID, CFind.StudyID };

        fillStudy(params, keysStudies);

        return params.getPatients();
    }

    public static List<Patient> buildFromSeriesInstanceUID(DicomQueryParams params, String seriesInstanceUID)
        throws Exception {
        if (!StringUtil.hasText(seriesInstanceUID)) {
            return null;
        }

        DicomParam[] keysSeries = {
            // Matching Keys
            new DicomParam(Tag.SeriesInstanceUID, seriesInstanceUID),
            // Return Keys
            CFind.PatientID, CFind.IssuerOfPatientID, CFind.PatientName, CFind.PatientBirthDate, CFind.PatientSex,
            CFind.ReferringPhysicianName, CFind.StudyDescription, CFind.StudyDate, CFind.StudyTime,
            CFind.AccessionNumber, CFind.StudyInstanceUID, CFind.StudyID, CFind.Modality, CFind.SeriesNumber,
            CFind.SeriesDescription };

        DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(), params.getCalledNode(), 0,
            QueryRetrieveLevel.SERIES, keysSeries);

        List<Attributes> series = state.getDicomRSP();
        if (series != null && series.size() > 0) {
            Attributes dataset = series.get(0);
            Patient patient = getPatient(params, dataset);
            Study study = getStudy(patient, dataset);
            for (Attributes seriesDataset : series) {
                fillInstance(params, seriesDataset, study);
            }
        }

        return params.getPatients();
    }

    public static List<Patient> buildFromSopInstanceUID(DicomQueryParams params, String sopInstanceUID)
        throws Exception {
        if (!StringUtil.hasText(sopInstanceUID)) {
            return null;
        }

        DicomParam[] keysInstance = {
            // Matching Keys
            new DicomParam(Tag.SOPInstanceUID, sopInstanceUID),
            // Return Keys
            CFind.PatientID, CFind.IssuerOfPatientID, CFind.PatientName, CFind.PatientBirthDate, CFind.PatientSex,
            CFind.ReferringPhysicianName, CFind.StudyDescription, CFind.StudyDate, CFind.StudyTime,
            CFind.AccessionNumber, CFind.StudyInstanceUID, CFind.StudyID, CFind.SeriesInstanceUID, CFind.Modality,
            CFind.SeriesNumber, CFind.SeriesDescription };

        DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(), params.getCalledNode(), 0,
            QueryRetrieveLevel.IMAGE, keysInstance);

        List<Attributes> instances = state.getDicomRSP();
        if (instances != null && instances.size() > 0) {
            Attributes dataset = instances.get(0);
            Patient patient = getPatient(params, dataset);
            Study study = getStudy(patient, dataset);
            Series s = getSeries(study, dataset);
            for (Attributes instanceDataSet : instances) {
                String sopUID = instanceDataSet.getString(Tag.SOPInstanceUID);
                if (sopUID != null) {
                    SOPInstance sop = new SOPInstance(sopUID);
                    sop.setInstanceNumber(instanceDataSet.getString(Tag.InstanceNumber));
                    s.addSOPInstance(sop);
                }
            }
        }
        return params.getPatients();
    }

    private static WadoMessage fillStudy(DicomQueryParams params, DicomParam[] keysStudies) throws Exception {
        DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(), params.getCalledNode(), 0,
            QueryRetrieveLevel.STUDY, keysStudies);

        List<Attributes> studies = state.getDicomRSP();
        if (studies != null) {
            for (Attributes studyDataSet : studies) {
                fillSeries(params, studyDataSet);
            }
        }
        return null;
    }

    private static void fillSeries(DicomQueryParams params, Attributes studyDataSet) throws Exception {
        String studyInstanceUID = studyDataSet.getString(Tag.StudyInstanceUID);
        if (StringUtil.hasText(studyInstanceUID)) {

            DicomParam[] keysSeries = {
                // Matching Keys
                new DicomParam(Tag.StudyInstanceUID, studyInstanceUID),
                // Return Keys
                CFind.SeriesInstanceUID, CFind.Modality, CFind.SeriesNumber, CFind.SeriesDescription };

            DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(),
                params.getCalledNode(), 0, QueryRetrieveLevel.SERIES, keysSeries);

            List<Attributes> series = state.getDicomRSP();
            if (series != null) {
                // Get patient from each study in case IssuerOfPatientID is different
                Patient patient = getPatient(params, studyDataSet);
                Study study = getStudy(patient, studyDataSet);
                for (Attributes seriesDataset : series) {
                    fillInstance(params, seriesDataset, study);
                }
            }
        }
    }

    private static void fillInstance(DicomQueryParams params, Attributes seriesDataset, Study study) throws Exception {
        String serieInstanceUID = seriesDataset.getString(Tag.SeriesInstanceUID);
        if (StringUtil.hasText(serieInstanceUID)) {
            DicomParam[] keysInstance = {
                // Matching Keys
                new DicomParam(Tag.StudyInstanceUID, study.getStudyInstanceUID()),
                new DicomParam(Tag.SeriesInstanceUID, serieInstanceUID),
                // Return Keys
                CFind.SOPInstanceUID, CFind.InstanceNumber };
            DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(),
                params.getCalledNode(), 0, QueryRetrieveLevel.IMAGE, keysInstance);

            List<Attributes> instances = state.getDicomRSP();
            if (instances != null) {
                Series s = getSeries(study, seriesDataset);

                for (Attributes instanceDataSet : instances) {
                    String sopUID = instanceDataSet.getString(Tag.SOPInstanceUID);
                    if (sopUID != null) {
                        SOPInstance sop = new SOPInstance(sopUID);
                        sop.setInstanceNumber(instanceDataSet.getString(Tag.InstanceNumber));
                        s.addSOPInstance(sop);
                    }
                }
            }
        }
    }

    protected static Patient getPatient(DicomQueryParams params, final Attributes patientDataset) throws Exception {
        if (patientDataset == null) {
            throw new IllegalArgumentException("patientDataset cannot be null");
        }
        final List<Patient> patientList = params.getPatients();

        // Request at SERIES level without relational model can respond without a Patient ID
        if (!patientDataset.contains(Tag.PatientID)) {
            // Request at IMAGE level without relational model can respond without a Study Instance UID
            if (!patientDataset.contains(Tag.StudyInstanceUID)) {
                String seriesInstanceUID = patientDataset.getString(Tag.SeriesInstanceUID);
                if (!StringUtil.hasText(seriesInstanceUID)) {
                    throw new Exception("Cannot get Series Instance UID with C-Find");
                }
                DicomParam[] keysSeries = {
                    // Matching Keys
                    new DicomParam(Tag.SeriesInstanceUID, patientDataset.getString(Tag.SeriesInstanceUID)),
                    // Return Keys
                    CFind.StudyInstanceUID, CFind.Modality, CFind.SeriesNumber, CFind.SeriesDescription };

                DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(),
                    params.getCalledNode(), 0, QueryRetrieveLevel.SERIES, keysSeries);
                List<Attributes> series = state.getDicomRSP();
                if (series.isEmpty()) {
                    throw new Exception("Get empty C-Find reply at Series level for " + seriesInstanceUID);
                }
                patientDataset.addAll(series.get(0));
            }

            String studyInstanceUID = patientDataset.getString(Tag.StudyInstanceUID);
            if (!StringUtil.hasText(studyInstanceUID)) {
                throw new Exception("Cannot get Study Instance UID with C-Find");
            }
            DicomParam[] keysStudies = {
                // Matching Keys
                new DicomParam(Tag.StudyInstanceUID, studyInstanceUID),
                // Return Keys
                CFind.PatientID, CFind.IssuerOfPatientID, CFind.PatientName, CFind.PatientBirthDate, CFind.PatientSex,
                CFind.ReferringPhysicianName, CFind.StudyDescription, CFind.StudyDate, CFind.StudyTime,
                CFind.AccessionNumber, CFind.StudyID };

            DicomState state = CFind.process(params.getAdvancedParams(), params.getCallingNode(),
                params.getCalledNode(), 0, QueryRetrieveLevel.STUDY, keysStudies);

            List<Attributes> studies = state.getDicomRSP();
            if (studies.isEmpty()) {
                throw new Exception("Get empty C-Find reply at Study level for " + studyInstanceUID);
            }
            patientDataset.addAll(studies.get(0));

        }
        String id = patientDataset.getString(Tag.PatientID, "Unknown");
        String ispid = patientDataset.getString(Tag.IssuerOfPatientID);
        for (Patient p : patientList) {
            if (p.hasSameUniqueID(id, ispid)) {
                return p;
            }
        }
        Patient p = new Patient(id, ispid);
        p.setPatientName(patientDataset.getString(Tag.PatientName));
        p.setPatientBirthDate(patientDataset.getString(Tag.PatientBirthDate));
        // p.setPatientBirthTime(patientDataset.getString(Tag.PatientBirthTime));
        p.setPatientSex(patientDataset.getString(Tag.PatientSex));
        patientList.add(p);
        return p;
    }

    protected static Study getStudy(Patient patient, final Attributes studyDataset) throws Exception {
        if (studyDataset == null) {
            throw new IllegalArgumentException("studyDataset cannot be null");
        }
        String uid = studyDataset.getString(Tag.StudyInstanceUID);
        Study s = patient.getStudy(uid);
        if (s == null) {
            s = new Study(uid);
            s.setStudyDescription(studyDataset.getString(Tag.StudyDescription));
            s.setStudyDate(studyDataset.getString(Tag.StudyDate));
            s.setStudyTime(studyDataset.getString(Tag.StudyTime));
            s.setAccessionNumber(studyDataset.getString(Tag.AccessionNumber));
            s.setStudyID(studyDataset.getString(Tag.StudyID));
            s.setReferringPhysicianName(studyDataset.getString(Tag.ReferringPhysicianName));
            patient.addStudy(s);
        }
        return s;
    }

    protected static Series getSeries(Study study, final Attributes seriesDataset) throws Exception {
        if (seriesDataset == null) {
            throw new IllegalArgumentException("seriesDataset cannot be null");
        }
        String uid = seriesDataset.getString(Tag.SeriesInstanceUID);
        Series s = study.getSeries(uid);
        if (s == null) {
            s = new Series(uid);
            s.setModality(seriesDataset.getString(Tag.Modality));
            s.setSeriesNumber(seriesDataset.getString(Tag.SeriesNumber));
            s.setSeriesDescription(seriesDataset.getString(Tag.SeriesDescription));
            study.addSeries(s);
        }
        return s;
    }

}
