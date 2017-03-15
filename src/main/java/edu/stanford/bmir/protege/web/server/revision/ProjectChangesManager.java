package edu.stanford.bmir.protege.web.server.revision;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gwt.safehtml.shared.SafeHtml;
import edu.stanford.bmir.protege.web.server.change.ChangeRecordComparator;
import edu.stanford.bmir.protege.web.server.diff.DiffElementRenderer;
import edu.stanford.bmir.protege.web.server.diff.Revision2DiffElementsTranslator;
import edu.stanford.bmir.protege.web.server.inject.project.RootOntology;
import edu.stanford.bmir.protege.web.server.renderer.RenderingManager;
import edu.stanford.bmir.protege.web.server.shortform.WebProtegeOntologyIRIShortFormProvider;
import edu.stanford.bmir.protege.web.shared.axiom.AxiomIRISubjectProvider;
import edu.stanford.bmir.protege.web.shared.change.ProjectChange;
import edu.stanford.bmir.protege.web.shared.diff.DiffElement;
import edu.stanford.bmir.protege.web.shared.entity.OWLEntityData;
import edu.stanford.bmir.protege.web.shared.pagination.Page;
import org.semanticweb.owlapi.change.AxiomChangeData;
import org.semanticweb.owlapi.change.OWLOntologyChangeData;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.model.*;

import javax.inject.Inject;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Matthew Horridge
 * Stanford Center for Biomedical Informatics Research
 * 27/05/15
 */
public class ProjectChangesManager {

    public static final int DEFAULT_CHANGE_LIMIT = 50;

    private final RevisionManager changeManager;

    private final EntitiesByRevisionCache entitiesByRevisionCache;

    private final OWLOntology rootOntology;

    private final RenderingManager browserTextProvider;

    private final Comparator<OWLAxiom> axiomComparator;

    private final Comparator<? super OWLAnnotation> annotationComparator;

    @Inject
    public ProjectChangesManager(RevisionManager changeManager,
                                 EntitiesByRevisionCache entitiesByRevisionCache,
                                 @RootOntology OWLOntology rootOntology,
                                 RenderingManager browserTextProvider,
                                 Comparator<OWLAxiom> axiomComparator,
                                 Comparator<? super OWLAnnotation> annotationComparator) {
        this.changeManager = changeManager;
        this.entitiesByRevisionCache = entitiesByRevisionCache;
        this.rootOntology = rootOntology;
        this.browserTextProvider = browserTextProvider;
        this.axiomComparator = axiomComparator;
        this.annotationComparator = annotationComparator;
    }

    public ImmutableList<ProjectChange> getProjectChanges(Optional<OWLEntity> subject) {
        ImmutableList.Builder<ProjectChange> changes = ImmutableList.builder();
        for (Revision revision : changeManager.getRevisions()) {
            getProjectChangesForRevision(revision, subject, changes);
        }
        return changes.build();
    }

    public ImmutableList<ProjectChange> getProjectChangesForSubjectInRevision(OWLEntity subject, Revision revision) {
        ImmutableList.Builder<ProjectChange> resultBuilder = ImmutableList.builder();
        getProjectChangesForRevision(revision, Optional.of(subject), resultBuilder);
        return resultBuilder.build();
    }

    private void getProjectChangesForRevision(Revision revision,
                                              Optional<OWLEntity> subject,
                                              ImmutableList.Builder<ProjectChange> changesBuilder) {
        if (subject.isPresent() && !entitiesByRevisionCache.containsEntity(revision, subject.get())) {
            return;
        }

        Map<Optional<IRI>, List<OWLOntologyChangeRecord>> recordsBySubject =
                revision.getChanges().stream()
                        .collect(Collectors.groupingBy(rec -> getAxiom(rec)));

        List<OWLOntologyChangeRecord> limitedRecords = new ArrayList<>();
        ImmutableSet.Builder<OWLEntityData> subjectsBuilder = ImmutableSet.builder();
        final int totalChanges;
        if (subject.isPresent()) {
            List<OWLOntologyChangeRecord> records = recordsBySubject.get(subject.map(OWLEntity::getIRI));
            totalChanges = records.size();
            limitedRecords.addAll(records);
            subjectsBuilder.add(browserTextProvider.getRendering(subject.get()));
        }
        else {
            totalChanges = revision.getSize();
            for (Map.Entry<Optional<IRI>, List<OWLOntologyChangeRecord>> entry : recordsBySubject.entrySet()) {
                limitedRecords.addAll(entry.getValue());
                if (limitedRecords.size() >= DEFAULT_CHANGE_LIMIT) {
                    break;
                }
            }
        }

        Revision2DiffElementsTranslator translator = new Revision2DiffElementsTranslator(
                new WebProtegeOntologyIRIShortFormProvider(rootOntology)
        );

        List<DiffElement<String, OWLOntologyChangeRecord>> axiomDiffElements = translator.getDiffElementsFromRevision(
                limitedRecords);
        sortDiff(axiomDiffElements);
        List<DiffElement<String, SafeHtml>> renderedDiffElements = renderDiffElements(axiomDiffElements);
        int pageElements = renderedDiffElements.size();
        int pageCount = totalChanges / pageElements + (totalChanges % pageElements);
        Page<DiffElement<String, SafeHtml>> page = new Page<>(
                1,
                pageCount,
                renderedDiffElements,
                totalChanges
        );
        ProjectChange projectChange = new ProjectChange(
                subjectsBuilder.build(),
                revision.getRevisionNumber(),
                revision.getUserId(),
                revision.getTimestamp(),
                revision.getHighLevelDescription(),
                totalChanges,
                page);
        changesBuilder.add(projectChange);
    }

    private static Optional<IRI> getAxiom(OWLOntologyChangeRecord rec) {
        OWLOntologyChangeData data = rec.getData();
        if (data instanceof AxiomChangeData) {
            OWLAxiom axiom = ((AxiomChangeData) data).getAxiom();
            return getSubject(axiom);
        }
        else {
            return Optional.empty();
        }
    }

    private static Optional<IRI> getSubject(OWLAxiom axiom) {
        AxiomIRISubjectProvider subjectProvider = new AxiomIRISubjectProvider(IRI::compareTo);
        return subjectProvider.getSubject(axiom);
    }

    private List<DiffElement<String, SafeHtml>> renderDiffElements(List<DiffElement<String, OWLOntologyChangeRecord>> axiomDiffElements) {

        List<DiffElement<String, SafeHtml>> diffElements = new ArrayList<>();
        DiffElementRenderer<String> renderer = new DiffElementRenderer<>(browserTextProvider);
        for (DiffElement<String, OWLOntologyChangeRecord> axiomDiffElement : axiomDiffElements) {
            diffElements.add(renderer.render(axiomDiffElement));
        }
        return diffElements;
    }


    private void sortDiff(List<DiffElement<String, OWLOntologyChangeRecord>> diffElements) {
        final Comparator<OWLOntologyChangeRecord> changeRecordComparator = new ChangeRecordComparator(axiomComparator,
                                                                                                      annotationComparator);
        Comparator<DiffElement<String, OWLOntologyChangeRecord>> c =
                Comparator
                        .comparing((Function<DiffElement<String, OWLOntologyChangeRecord>, OWLOntologyChangeRecord>)
                                           DiffElement::getLineElement, changeRecordComparator)
                        .thenComparing(DiffElement::getDiffOperation)
                        .thenComparing(DiffElement::getSourceDocument);
        diffElements.sort(c);
    }
}
