package sg.schoolmatch.boundary.ui.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;

/** {@link ReferenceLocationStore}: the starting point and address choices in the HTTP session (DC-23). */
class ReferenceLocationStoreTest {

    private final ReferenceLocationStore store = new ReferenceLocationStore();
    private final MockHttpSession session = new MockHttpSession();

    private final ReferenceLocation bishan =
            new ReferenceLocation(new Coordinate(1.3500, 103.8480), LocationSource.MANUAL_ENTRY, "BISHAN STREET 13");
    private final ReferenceLocation bishanPark =
            new ReferenceLocation(new Coordinate(1.3620, 103.8460), LocationSource.MANUAL_ENTRY, "BISHAN PARK");

    @Test
    @Tag("FR-FILTER-04")
    @DisplayName("TC-RefLocStore-01: set, get and clear the starting point")
    void setGetClear() {
        assertThat(store.get(session)).isEmpty();

        store.set(session, bishan);
        assertThat(store.get(session)).containsSame(bishan);
        assertThat(session.getAttribute(ReferenceLocationStore.CURRENT)).isSameAs(bishan);

        store.clear(session);
        assertThat(store.get(session)).isEmpty();
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-RefLocStore-02: candidates are stored as a copy, and choosing a location drops them")
    void candidates() {
        List<ReferenceLocation> found = new ArrayList<>(List.of(bishan, bishanPark));
        store.setCandidates(session, found);
        found.clear();   // the caller's list does not change what is stored

        assertThat(store.getCandidates(session)).containsExactly(bishan, bishanPark);

        store.set(session, bishanPark);
        assertThat(store.getCandidates(session)).isEmpty();
        assertThat(store.get(session)).containsSame(bishanPark);

        store.setCandidates(session, List.of(bishan));
        store.clearCandidates(session);
        assertThat(store.getCandidates(session)).isEmpty();
        assertThat(store.get(session)).containsSame(bishanPark);   // clearing choices keeps the starting point
    }

    @Test
    @Tag("FR-FILTER-04")
    @DisplayName("TC-RefLocStore-03: without a session nothing is found and no session is created")
    void noSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/schools");

        assertThat(store.get(request)).isEmpty();
        assertThat(store.getCandidates(request)).isEmpty();
        assertThat(store.get((jakarta.servlet.http.HttpSession) null)).isEmpty();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    @Tag("FR-FILTER-04")
    @DisplayName("TC-RefLocStore-04: a wrong object under the key is ignored, not a ClassCastException")
    void wrongTypeIgnored() {
        session.setAttribute(ReferenceLocationStore.CURRENT, "Bishan");
        session.setAttribute(ReferenceLocationStore.CANDIDATES, "not a list");

        assertThat(store.get(session)).isEmpty();
        assertThat(store.getCandidates(session)).isEmpty();
    }

    @Test
    @Tag("FR-FILTER-04")
    @DisplayName("TC-RefLocStore-05: what is stored can be serialised (the servlet container may save sessions)")
    void storedValuesAreSerializable() throws IOException, ClassNotFoundException {
        store.set(session, bishan);
        store.setCandidates(session, List.of(bishan, bishanPark));

        Object current = copyBySerialisation(session.getAttribute(ReferenceLocationStore.CURRENT));
        Object candidates = copyBySerialisation(session.getAttribute(ReferenceLocationStore.CANDIDATES));

        assertThat(((ReferenceLocation) current).getCoordinate()).isEqualTo(bishan.getCoordinate());
        assertThat((List<?>) candidates).hasSize(2);
    }

    private static Object copyBySerialisation(Object value) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }
}
