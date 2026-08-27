package et.elisa.iwf.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import et.elisa.iwf.diameter.DiaApp;
import et.elisa.iwf.diameter.DiaCmd;
import et.elisa.iwf.map.MapOp;
import et.elisa.iwf.mapping.AvpTransform;

class Ts29305TableTest {

    @Test
    void coversCoreS6aS6dFlows() {
        var ops = Ts29305Table.all().stream()
                .map(MappingEntry::mapOp).collect(Collectors.toSet());
        assertTrue(ops.containsAll(Set.of(
                MapOp.UPDATE_GPRS_LOCATION,
                MapOp.SEND_AUTHENTICATION_INFO,
                MapOp.PURGE_MS,
                MapOp.INSERT_SUBSCRIBER_DATA,
                MapOp.CANCEL_LOCATION,
                MapOp.NOTIFY_GPRS)));
    }

    @Test
    void coversFullRoamingSet() {
        var ops = Ts29305Table.all().stream()
                .map(MappingEntry::mapOp).collect(Collectors.toSet());
        // S6c CS Voice / SMS
        assertTrue(ops.containsAll(Set.of(
                MapOp.SEND_ROUTING_INFO_FOR_CS,
                MapOp.PROVIDE_ROAMING_NUMBER,
                MapOp.SEND_INFO_FOR_OUTGOING_SM,
                MapOp.SEND_INFO_FOR_INCOMING_SM,
                MapOp.REPORT_SM_DELIVERY_STATUS)));
        // S13 CAMEL
        assertTrue(ops.containsAll(Set.of(
                MapOp.PROVIDE_SUBSCRIBER_INFO,
                MapOp.DEBUT_TRACE,
                MapOp.ADD_CAMEL_SUBSCRIPTION_INFO)));
        // SLg LCS
        assertTrue(ops.containsAll(Set.of(
                MapOp.PROVIDE_SUBSCRIBER_LOCATION,
                MapOp.SUBSCRIBER_LOCATION_REPORT)));
        // SS
        assertTrue(ops.containsAll(Set.of(
                MapOp.REGISTER_SS,
                MapOp.ACTIVATE_SS,
                MapOp.DEACTIVATE_SS,
                MapOp.INTERROGATE_SS,
                MapOp.ERASE_SS)));
        // Handover
        assertTrue(ops.containsAll(Set.of(
                MapOp.PREPARE_HO,
                MapOp.PREPARE_SUBSEQUENT_HO,
                MapOp.ALLOCATE_HANDOVER_NUMBER)));
    }

    @Test
    void everyEntryHasSpecRefAndAtLeastOneDirection() {
        for (MappingEntry e : Ts29305Table.all()) {
            assertTrue(e.specRef().startsWith("TS 29.305"), e.toString());
            assertTrue(e.mapToDia() || e.diaToMap(), e.toString());
        }
    }

    @Test
    void everyEntryCarriesDiaApp() {
        for (MappingEntry e : Ts29305Table.all()) {
            assertTrue(e.appId() > 0, e + " must carry a Diameter application ID");
        }
    }

    @Test
    void mapToDiaRowsCarryStructuredTransforms() {
        var ulr = Ts29305Table.forMapOp(MapOp.UPDATE_GPRS_LOCATION).orElseThrow();
        assertTrue(ulr.mapToDia());
        assertFalse(ulr.transforms().isEmpty(), "client-sendable ULR must declare AVP transforms");
        var imsi = ulr.transforms().stream()
                .filter(t -> t.diaAvpName().equals("User-Name")).findFirst().orElseThrow();
        assertEquals(AvpTransform.TransformKind.IDENTITY, imsi.kind());
        assertTrue(imsi.required());
        var plmn = ulr.transforms().stream()
                .filter(t -> t.diaAvpName().equals("Visited-PLMN-Id")).findFirst().orElseThrow();
        assertEquals(1407, plmn.diaAvpCode());
        assertEquals(AvpTransform.TransformKind.TBCD_PLMN, plmn.kind());
        assertFalse(plmn.required(), "PLMN has a default (45204) on the leg");
    }

    @Test
    void clientTransformsPinnedPerOp() {
        var air = Ts29305Table.forMapOp(MapOp.SEND_AUTHENTICATION_INFO).orElseThrow();
        assertEquals(3, air.transforms().size(), "AIR: User-Name + Visited-PLMN + Requested-EUTRAN-Auth-Info");
        assertEquals("Requested-EUTRAN-Authentication-Info",
                air.transforms().get(2).diaAvpName());
        assertEquals(1408, air.transforms().get(2).diaAvpCode());

        var pur = Ts29305Table.forMapOp(MapOp.PURGE_MS).orElseThrow();
        assertEquals(2, pur.transforms().size(), "PUR: User-Name + Visited-PLMN");

        var nor = Ts29305Table.forMapOp(MapOp.NOTIFY_GPRS).orElseThrow();
        assertEquals(3, nor.transforms().size(), "NOR: User-Name + Visited-PLMN + NOR-Flags");
        assertEquals(AvpTransform.TransformKind.NOR_FLAGS, nor.transforms().get(2).kind());
    }

    @Test
    void clientRowsAreMappedStatus() {
        for (MapOp op : Set.of(MapOp.UPDATE_GPRS_LOCATION,
                MapOp.SEND_AUTHENTICATION_INFO, MapOp.PURGE_MS, MapOp.NOTIFY_GPRS)) {
            var e = Ts29305Table.forMapOp(op).orElseThrow();
            assertEquals(MappingEntry.Status.MAPPED, e.status(),
                    op + " has transforms pinned by unit tests — promoted PLANNED→MAPPED");
        }
    }

    @Test
    void diaToMapRowsHaveNoClientTransformsYet() {
        for (MapOp op : Set.of(MapOp.INSERT_SUBSCRIBER_DATA,
                MapOp.DELETE_SUBSCRIBER_DATA, MapOp.CANCEL_LOCATION)) {
            var e = Ts29305Table.forMapOp(op).orElseThrow();
            assertFalse(e.mapToDia(), e + " is HSS-initiated only");
            assertTrue(e.transforms().isEmpty(),
                    e + " client transforms land with the MAP leg (M-IWF-3)");
        }
    }

    @Test
    void lookupsResolveBothDirections() {
        assertTrue(Ts29305Table.forMapOp(MapOp.UPDATE_GPRS_LOCATION).isPresent());
        assertEquals(DiaCmd.ULR, Ts29305Table.forMapOp(MapOp.UPDATE_GPRS_LOCATION)
                .orElseThrow().diaCmd());
        assertEquals(MapOp.CANCEL_LOCATION, Ts29305Table.forDiaCmd(DiaCmd.CLR)
                .orElseThrow().mapOp());
    }

    @Test
    void s6cMappingResolves() {
        var sriCs = Ts29305Table.forMapOp(MapOp.SEND_ROUTING_INFO_FOR_CS).orElseThrow();
        assertEquals(DiaCmd.SRR_S6C, sriCs.diaCmd());
        assertEquals(DiaApp.S6C.appId(), sriCs.appId());
        assertTrue(sriCs.mapToDia());
    }

    @Test
    void s13MappingResolves() {
        var psi = Ts29305Table.forMapOp(MapOp.PROVIDE_SUBSCRIBER_INFO).orElseThrow();
        assertEquals(DiaCmd.ECR_S13, psi.diaCmd());
        assertEquals(DiaApp.S13.appId(), psi.appId());
    }

    @Test
    void slgMappingResolves() {
        var psl = Ts29305Table.forMapOp(MapOp.PROVIDE_SUBSCRIBER_LOCATION).orElseThrow();
        assertEquals(DiaCmd.PLR_SLG, psl.diaCmd());
        assertEquals(DiaApp.SLG.appId(), psl.appId());
    }

    @Test
    void engineDispatchesMapToDiameterDraftsOnlyForMapToDiaRows() {
        IwfEngine engine = new IwfEngine();
        var draft = engine.mapToDiameter(MapOp.UPDATE_GPRS_LOCATION,
                Map.of("IMSI", "4520402000000001"));
        assertTrue(draft.isPresent());
        assertEquals(DiaCmd.ULR, draft.orElseThrow().diaCmd());
        assertEquals(DiaCmd.S6A_APP_ID, draft.orElseThrow().appId());

        var sriDraft = engine.mapToDiameter(MapOp.SEND_ROUTING_INFO_FOR_CS,
                Map.of("imsi", "4520402000000001"));
        assertTrue(sriDraft.isPresent());
        assertEquals(DiaCmd.SRR_S6C, sriDraft.orElseThrow().diaCmd());
        assertEquals(DiaApp.S6C.appId(), sriDraft.orElseThrow().appId());

        assertTrue(engine.diameterToMap(DiaCmd.CLR, Map.of("IMSI", "x")).isPresent());
        assertTrue(engine.diameterToMap(DiaCmd.ULR, Map.of()).isEmpty(),
                "ULR is mapToDia-only");
    }

    @Test
    void mapOpCodesMatchTs293002() {
        assertEquals(48, MapOp.UPDATE_GPRS_LOCATION.opCode());
        assertEquals(56, MapOp.SEND_AUTHENTICATION_INFO.opCode());
        assertEquals(51, MapOp.PURGE_MS.opCode());
        assertEquals(7, MapOp.INSERT_SUBSCRIBER_DATA.opCode());
        assertEquals(3, MapOp.CANCEL_LOCATION.opCode());
        assertEquals(2, MapOp.UPDATE_LOCATION.opCode());
        assertEquals(5, MapOp.SEND_ROUTING_INFO_FOR_CS.opCode());
        assertEquals(22, MapOp.PROVIDE_ROAMING_NUMBER.opCode());
        assertEquals(69, MapOp.PROVIDE_SUBSCRIBER_INFO.opCode());
        assertEquals(23, MapOp.PROVIDE_SUBSCRIBER_LOCATION.opCode());
        assertEquals(50, MapOp.REGISTER_SS.opCode());
        assertEquals(70, MapOp.UNSTRUCTURED_SS_REQUEST.opCode());
        assertEquals(61, MapOp.PREPARE_HO.opCode());
        assertEquals(60, MapOp.READY_FOR_SM.opCode());
    }

    @Test
    void diaCmdCodesMatchTs29272() {
        assertEquals(316, DiaCmd.ULR.cmdCode());
        assertEquals(318, DiaCmd.AIR.cmdCode());
        assertEquals(321, DiaCmd.PUR.cmdCode());
        assertEquals(317, DiaCmd.CLR.cmdCode());
        assertEquals(319, DiaCmd.IDR.cmdCode());
        assertEquals(320, DiaCmd.DSR.cmdCode());
        assertEquals(323, DiaCmd.NOR.cmdCode());
        assertEquals(16777251L, DiaCmd.S6A_APP_ID);
        assertEquals(324, DiaCmd.SRR_S6C.cmdCode());
        assertEquals(325, DiaCmd.ASM_S6C.cmdCode());
        assertEquals(324, DiaCmd.ECR_S13.cmdCode());
        assertEquals(8388630, DiaCmd.RIR_SLH.cmdCode());
        assertEquals(8388624, DiaCmd.PLR_SLG.cmdCode());
        assertEquals(8388625, DiaCmd.LRR_SLG.cmdCode());
    }

    @Test
    void diaCmdCarriesCorrectApp() {
        assertEquals(DiaApp.S6A, DiaCmd.ULR.app());
        assertEquals(DiaApp.S6C, DiaCmd.SRR_S6C.app());
        assertEquals(DiaApp.S13, DiaCmd.ECR_S13.app());
        assertEquals(DiaApp.SLH, DiaCmd.RIR_SLH.app());
        assertEquals(DiaApp.SLG, DiaCmd.PLR_SLG.app());
        assertEquals(DiaApp.SH, DiaCmd.UDR_SH.app());
        assertEquals(DiaApp.RX, DiaCmd.AAR_RX.app());
        assertEquals(DiaApp.GX, DiaCmd.CCR_GX.app());
        assertEquals(DiaApp.CX_DX, DiaCmd.UAR_CX.app());
        assertEquals(DiaApp.SWX, DiaCmd.MAR_SWX.app());
    }
}
