package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.*

// Tour II — The Living Machine: mouth to mitosis.
// Landmark scenes, drawn by StereoBodyRenderer.drawLandmarks via the stop's Scene.
//
// Every scene is sized from real dimensions at the stop's stated scale (1 unit = Mote length / 1.5):
//   mouth 8 mm, gut 0.8 mm, phage 80 nm, liver / kidney / marrow 8 µm, muscle / division 0.8 µm,
//   V(D)J / factory 8 nm, highway 80 nm, ATP synthase 0.8 nm per unit.
// Static anatomy is baked once into vertex-coloured meshes that follow the curve of the rail
// (see t2Bend); moving parts are drawn per frame in the rail frame at their own position. Several
// structures are far bigger than the passage (a bacterium, a glomerulus, a trabecula, ATP synthase,
// a dividing cell): while its stop is the current one a scene clears the depth buffer, draws its
// anatomy over the passage wall, and then puts the wall's depth back (t2Open).

/** What drifts past at each stop of this tour, by stop index; stops not listed use DriftSpec.forAmb. */
internal val MACHINE_DRIFT: Map<Int, DriftSpec> = mapOf(
    // The oesophagus and stomach at 8 mm a unit: nothing loose is big enough to see; the scene
    // draws the bolus, the chyme and its food particles.
    1 to DriftSpec.NONE,
    // Gut and colon: at 0.8 mm a unit the bacteria are sub-pixel (the renderer skips them); once the
    // Mote is 12 µm in the colon (8 µm a unit) they are 2-µm rods everywhere.
    2 to DriftSpec.of(BodyField.BACTERIUM to 0.85f, BodyField.CHYLE to 0.15f, density = 0.9f, flow = 0.3f),
    // The rest of the colon hold, then the phage stop at 80 nm a unit, where a whole E. coli is 25
    // units (too big to drift, so skipped) and only lumen proteins show.
    3 to DriftSpec.of(BodyField.BACTERIUM to 0.7f, BodyField.CHYLE to 0.1f, BodyField.PROTEIN to 0.2f, density = 0.9f, flow = 0.2f),
    // A liver sinusoid carries mixed portal and arterial blood.
    4 to DriftSpec.of(BodyField.RED_CELL to 0.95f, BodyField.PLATELET to 0.05f, density = 0.25f, flow = 1.0f, oxy = false),
    // Bowman's space holds filtrate, not cells (the scene draws the filtrate).
    5 to DriftSpec.NONE,
    // Inside a skeletal-muscle fibre: no blood cells.
    6 to DriftSpec.NONE,
    // Red marrow: a few free cells drifting between the cords.
    7 to DriftSpec.of(BodyField.RED_CELL to 0.6f, BodyField.PLATELET to 0.4f, density = 0.05f, flow = 0.2f),
    // The nucleoplasm around a gene and the beta cell's ER: at 8 nm a unit a protein is as big as
    // the craft, so nothing drifts (the scenes draw the molecules that matter).
    8 to DriftSpec.NONE,
    9 to DriftSpec.of(BodyField.PROTEIN to 0.9f, BodyField.VESICLE to 0.1f, density = 0.2f, flow = 0.12f),
    10 to DriftSpec.NONE,
)

/**
 * Tour II's passage radius from the mouth to the stomach (rail progress 0..1, 60 units of rail):
 * the oral cavity and pharynx, the upper sphincter, ~31 units (25 cm at 8 mm a unit) of
 * oesophagus held open to 3 cm by the bolus, the lower sphincter, then the stomach. Elsewhere
 * the usual node-to-node radius [r].
 */
internal fun t2MachineRadius(p: Float, r: Float): Float {
    if (p <= 0f || p >= 1f) return r
    fun sm(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    return when {
        p < 0.23f -> 4.2f
        p < 0.27f -> 4.2f + (T2_OES_R - 4.2f) * sm(0.23f, 0.27f, p)
        p < 0.69f -> T2_OES_R
        p < 0.705f -> T2_OES_R - 0.3f * sm(0.69f, 0.705f, p)
        else -> (T2_OES_R - 0.3f) + (6f - T2_OES_R + 0.3f) * sm(0.705f, 0.78f, p)
    }
}
internal const val T2_OES_R = 1.9f

/**
 * The swallow's route: over the epiglottis, then 5 units (4 cm) down behind the larynx into the
 * upper oesophageal sphincter (rail progress 0.24-0.29 = 11.3-14.5 units past the mouth stop),
 * easing back to the stops' level along the oesophagus.
 */
internal fun t2MachineDropY(p: Float): Float {
    if (p <= 0f || p >= 1f) return 0f
    fun sm(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    return -T2_DIVE * sm(0.25f, 0.279f, p) * (1f - sm(0.42f, 0.66f, p))
}
internal const val T2_DIVE = 4.6f

// ------------------------------------------------------------------------------------ palette
internal val T2_ENAMEL = floatArrayOf(0.97f, 0.95f, 0.88f, 1f)
internal val T2_ENAMEL_NECK = floatArrayOf(0.93f, 0.88f, 0.74f, 1f)
internal val T2_GUM = floatArrayOf(0.96f, 0.52f, 0.58f, 1f)
internal val T2_PALATE = floatArrayOf(0.95f, 0.6f, 0.64f, 1f)
internal val T2_SOFT_PALATE = floatArrayOf(0.9f, 0.44f, 0.52f, 1f)
internal val T2_RUGA = floatArrayOf(0.99f, 0.72f, 0.74f, 1f)
internal val T2_LIP = floatArrayOf(0.86f, 0.34f, 0.42f, 1f)
internal val T2_SKIN = floatArrayOf(0.95f, 0.7f, 0.6f, 1f)
internal val T2_TONGUE = floatArrayOf(0.93f, 0.5f, 0.56f, 1f)
internal val T2_PAPILLA = floatArrayOf(0.99f, 0.62f, 0.64f, 1f)
internal val T2_FUNGIFORM = floatArrayOf(0.98f, 0.58f, 0.62f, 1f)
internal val T2_FILIFORM = floatArrayOf(0.98f, 0.82f, 0.82f, 1f)
internal val T2_PHARYNX = floatArrayOf(0.88f, 0.48f, 0.54f, 1f)
internal val T2_VALLECULA_T = floatArrayOf(0.7f, 0.3f, 0.38f, 1f)
internal val T2_RIMA = floatArrayOf(0.35f, 0.12f, 0.16f, 1f)
internal val T2_VALLECULA = floatArrayOf(0.7f, 0.3f, 0.38f, 1f)
internal val T2_TONSIL = floatArrayOf(0.93f, 0.52f, 0.54f, 1f)
internal val T2_EPIGLOTTIS = floatArrayOf(0.98f, 0.82f, 0.8f, 1f)
internal val T2_VOCAL_CORD = floatArrayOf(0.98f, 0.97f, 0.94f, 1f)
internal val T2_LARYNX = floatArrayOf(0.9f, 0.5f, 0.56f, 1f)
internal val T2_OES_LINING = floatArrayOf(0.97f, 0.8f, 0.8f, 1f)
internal val T2_OES_MUSCLE = floatArrayOf(0.85f, 0.46f, 0.5f, 1f)
internal val T2_GASTRIC = floatArrayOf(0.9f, 0.46f, 0.42f, 1f)
internal val T2_RUGA_G = floatArrayOf(0.95f, 0.54f, 0.48f, 1f)
internal val T2_PIT = floatArrayOf(0.55f, 0.2f, 0.22f, 1f)
internal val T2_PYLORUS = floatArrayOf(0.82f, 0.36f, 0.4f, 1f)
internal val T2_MUCUS = floatArrayOf(0.92f, 0.92f, 0.88f, 1f)
internal val T2_CHYME_ACID = floatArrayOf(0.8f, 0.72f, 0.4f, 1f)
internal val T2_FOOD_A = floatArrayOf(0.88f, 0.78f, 0.55f, 1f)
internal val T2_FOOD_B = floatArrayOf(0.7f, 0.5f, 0.3f, 1f)
internal val T2_VILLUS = floatArrayOf(0.95f, 0.6f, 0.62f, 1f)
internal val T2_VILLUS_TIP = floatArrayOf(0.99f, 0.69f, 0.69f, 1f)
internal val T2_VILLUS_GLASS = floatArrayOf(1f, 0.8f, 0.8f, 1f)
internal val T2_COLON = floatArrayOf(0.9f, 0.62f, 0.6f, 1f)
internal val T2_COLON_RIM = floatArrayOf(0.95f, 0.72f, 0.7f, 1f)
internal val T2_VILLUS_EDGE = floatArrayOf(0.8f, 0.45f, 0.5f, 1f)
internal val T2_VENULE = floatArrayOf(0.62f, 0.3f, 0.62f, 1f)
internal val T2_CRYPT = floatArrayOf(0.62f, 0.24f, 0.3f, 1f)
internal val T2_LACTEAL = floatArrayOf(0.98f, 0.98f, 0.9f, 1f)
internal val T2_CHYME = floatArrayOf(0.86f, 0.76f, 0.55f, 1f)
internal val T2_ECOLI = floatArrayOf(0.5f, 0.78f, 0.42f, 1f)
internal val T2_PERIPLASM = floatArrayOf(0.55f, 0.85f, 0.5f, 1f)
internal val T2_ECOLI_OM = floatArrayOf(0.78f, 0.97f, 0.66f, 1f)
internal val T2_PHAGE_HEAD = floatArrayOf(0.74f, 0.7f, 1f, 1f)
internal val T2_PHAGE_TAIL = floatArrayOf(0.82f, 0.82f, 0.93f, 1f)
internal val T2_PHAGE_PLATE = floatArrayOf(0.64f, 0.66f, 0.9f, 1f)
internal val T2_DNA = floatArrayOf(0.62f, 0.82f, 1f, 1f)
internal val T2_FLAGELLUM = floatArrayOf(0.9f, 1f, 0.75f, 1f)
internal val T2_HEPATOCYTE = floatArrayOf(0.86f, 0.56f, 0.52f, 1f)
internal val T2_HEPATOCYTE_BACK = floatArrayOf(0.62f, 0.27f, 0.28f, 1f)
internal val T2_NUCLEUS = floatArrayOf(0.52f, 0.34f, 0.72f, 1f)
internal val T2_CANALICULUS = floatArrayOf(0.5f, 0.97f, 0.36f, 1f)
internal val T2_ENDOTHELIUM = floatArrayOf(0.97f, 0.88f, 0.86f, 1f)
internal val T2_ENDO_NUC = floatArrayOf(0.8f, 0.7f, 0.85f, 1f)
internal val T2_CENTRAL_VEIN = floatArrayOf(0.5f, 0.12f, 0.2f, 1f)
internal val T2_KUPFFER = floatArrayOf(0.7f, 0.8f, 0.66f, 1f)
internal val T2_HEP_NUC2 = floatArrayOf(0.5f, 0.4f, 0.65f, 1f)
internal val T2_FENESTRA = floatArrayOf(0.55f, 0.35f, 0.38f, 1f)
internal val T2_HEP_NUC3 = floatArrayOf(0.55f, 0.45f, 0.62f, 1f)
internal val T2_NUCLEOLUS = floatArrayOf(0.25f, 0.15f, 0.4f, 1f)
internal val T2_SIEVE = floatArrayOf(0.75f, 0.55f, 0.55f, 1f)
internal val T2_PHAGOSOME = floatArrayOf(0.55f, 0.18f, 0.2f, 1f)
internal val T2_STELLATE = floatArrayOf(0.8f, 0.78f, 0.55f, 1f)
internal val T2_LIPID_DROP = floatArrayOf(1f, 0.95f, 0.7f, 1f)
internal val T2_HEP_NUC = floatArrayOf(0.42f, 0.28f, 0.62f, 1f)
internal val T2_CAPILLARY = floatArrayOf(0.88f, 0.22f, 0.28f, 1f)
internal val T2_MESANGIUM = floatArrayOf(0.62f, 0.24f, 0.3f, 1f)
internal val T2_PODOCYTE_A = floatArrayOf(0.84f, 0.6f, 0.82f, 1f)
internal val T2_PODOCYTE_B = floatArrayOf(0.66f, 0.52f, 0.9f, 1f)
internal val T2_PARIETAL = floatArrayOf(0.66f, 0.5f, 0.52f, 1f)
internal val T2_TUBULE = floatArrayOf(0.92f, 0.6f, 0.6f, 1f)
internal val T2_BRUSH = floatArrayOf(0.99f, 0.88f, 0.84f, 1f)
internal val T2_PCT_SIDE = floatArrayOf(0.85f, 0.52f, 0.55f, 1f)
internal val T2_JG = floatArrayOf(0.95f, 0.85f, 0.6f, 1f)
internal val T2_JG_GRANULE = floatArrayOf(0.8f, 0.55f, 0.25f, 1f)
internal val T2_DISTAL = floatArrayOf(0.9f, 0.72f, 0.72f, 1f)
internal val T2_MACULA = floatArrayOf(0.9f, 0.7f, 0.7f, 1f)
internal val T2_EFFERENT = floatArrayOf(0.55f, 0.12f, 0.22f, 1f)
internal val T2_MESANGIUM_D = floatArrayOf(0.22f, 0.05f, 0.07f, 1f)
internal val T2_CAP_GLASS = floatArrayOf(0.95f, 0.7f, 0.72f, 1f)
internal val T2_PARIETAL_NUC = floatArrayOf(0.75f, 0.65f, 0.85f, 1f)
internal val T2_STALK = floatArrayOf(0.8f, 0.62f, 0.64f, 1f)
internal val T2_ARTERIOLE = floatArrayOf(0.82f, 0.16f, 0.2f, 1f)
internal val T2_FILTRATE = floatArrayOf(0.8f, 0.94f, 1f, 1f)
internal val T2_ZDISC = floatArrayOf(0.96f, 0.88f, 0.52f, 1f)
internal val T2_MYOSIN = floatArrayOf(0.72f, 0.24f, 0.3f, 1f)
internal val T2_MYOSIN_HEAD = floatArrayOf(0.98f, 0.5f, 0.52f, 1f)
internal val T2_ACTIN = floatArrayOf(0.88f, 0.74f, 0.68f, 1f)
internal val T2_MLINE = floatArrayOf(0.6f, 0.22f, 0.3f, 1f)
internal val T2_TTUBULE = floatArrayOf(0.26f, 0.5f, 0.58f, 1f)
internal val T2_SR = floatArrayOf(0.42f, 0.66f, 0.72f, 1f)
internal val T2_CALCIUM = floatArrayOf(0.55f, 0.95f, 1f, 1f)
internal val T2_MITO_PALE = floatArrayOf(0.9f, 0.62f, 0.45f, 1f)
internal val T2_ACTIN_PALE = floatArrayOf(0.9f, 0.8f, 0.74f, 1f)
internal val T2_MITO = floatArrayOf(0.96f, 0.56f, 0.26f, 1f)
internal val T2_MITO_CRISTA = floatArrayOf(0.99f, 0.74f, 0.4f, 1f)
internal val T2_BONE = floatArrayOf(0.96f, 0.91f, 0.78f, 1f)
internal val T2_OSTEOBLAST = floatArrayOf(0.76f, 0.62f, 0.9f, 1f)
internal val T2_OSTEOCLAST = floatArrayOf(0.88f, 0.52f, 0.64f, 1f)
internal val T2_SINUSOID = floatArrayOf(0.96f, 0.62f, 0.66f, 1f)
internal val T2_MEGA = floatArrayOf(0.92f, 0.76f, 0.92f, 1f)
internal val T2_MK_GRANULE = floatArrayOf(0.75f, 0.45f, 0.7f, 1f)
internal val T2_MARROW_BG = floatArrayOf(0.55f, 0.2f, 0.3f, 1f)
internal val T2_OSTEOBLAST_2 = floatArrayOf(0.55f, 0.45f, 0.85f, 1f)
internal val T2_LACUNA = floatArrayOf(0.8f, 0.68f, 0.55f, 1f)
internal val T2_BLAST_CYTO = floatArrayOf(0.6f, 0.65f, 0.95f, 1f)
internal val T2_BLAST_NUC = floatArrayOf(0.4f, 0.26f, 0.66f, 1f)
internal val T2_MYELO_CYTO = floatArrayOf(0.88f, 0.78f, 0.92f, 1f)
internal val T2_MYELO_NUC = floatArrayOf(0.5f, 0.35f, 0.7f, 1f)
internal val T2_MYELO_GRAN = floatArrayOf(0.95f, 0.55f, 0.65f, 1f)
internal val T2_NORMO_EARLY = floatArrayOf(0.45f, 0.5f, 0.85f, 1f)
internal val T2_NORMO_LATE = floatArrayOf(0.9f, 0.45f, 0.5f, 1f)
internal val T2_NORMO_NUC = floatArrayOf(0.2f, 0.1f, 0.3f, 1f)
internal val T2_LYMPH_NUC = floatArrayOf(0.35f, 0.3f, 0.7f, 1f)
internal val T2_MEGA_NUC = floatArrayOf(0.5f, 0.32f, 0.72f, 1f)
internal val T2_PLATELET = floatArrayOf(0.8f, 0.7f, 0.9f, 1f)
internal val T2_INGESTED = floatArrayOf(0.25f, 0.12f, 0.3f, 1f)
internal val T2_ERY_EARLY = floatArrayOf(0.45f, 0.5f, 0.85f, 1f)
internal val T2_ERY_LATE = floatArrayOf(0.9f, 0.45f, 0.5f, 1f)
internal val T2_PLT_GRAN = floatArrayOf(0.55f, 0.35f, 0.65f, 1f)
internal val T2_LYMPH_CYTO = floatArrayOf(0.72f, 0.8f, 0.98f, 1f)
internal val T2_LYMPH_NUC2 = floatArrayOf(0.25f, 0.22f, 0.55f, 1f)
internal val T2_BCELL_NUC = floatArrayOf(0.3f, 0.3f, 0.7f, 1f)
internal val T2_BCELL_CYTO = floatArrayOf(0.8f, 0.9f, 1f, 1f)
internal val T2_MACROPHAGE = floatArrayOf(0.82f, 0.84f, 0.7f, 1f)
internal val T2_PROERYTHRO = floatArrayOf(0.56f, 0.46f, 0.86f, 1f)
internal val T2_NORMOBLAST = floatArrayOf(0.9f, 0.4f, 0.46f, 1f)
internal val T2_RETIC = floatArrayOf(0.84f, 0.2f, 0.24f, 1f)
internal val T2_MYELOID = floatArrayOf(0.84f, 0.72f, 0.92f, 1f)
internal val T2_LYMPHOID = floatArrayOf(0.72f, 0.78f, 0.98f, 1f)
internal val T2_FAT = floatArrayOf(1f, 0.96f, 0.8f, 1f)
internal val T2_STROMA = floatArrayOf(0.92f, 0.86f, 0.7f, 1f)
internal val T2_HISTONE = floatArrayOf(0.5f, 0.4f, 0.75f, 1f)
internal val T2_DNA_WRAP = floatArrayOf(0.62f, 0.82f, 1f, 1f)
internal val T2_SEG_SPACER = floatArrayOf(0.55f, 0.55f, 0.65f, 1f)
internal val T2_SEG_V = floatArrayOf(1f, 0.35f, 0.55f, 1f)
internal val T2_SEG_D = floatArrayOf(0.35f, 1f, 0.45f, 1f)
internal val T2_SEG_J = floatArrayOf(0.35f, 0.6f, 1f, 1f)
internal val T2_SEG_C = floatArrayOf(0.75f, 0.45f, 1f, 1f)
internal val T2_BASEPAIR = floatArrayOf(0.85f, 0.85f, 0.9f, 1f)
internal val T2_DNA_GAP = floatArrayOf(0.5f, 0.5f, 0.55f, 1f)
internal val T2_RSS = floatArrayOf(1f, 1f, 1f, 1f)
internal val T2_RAG1 = floatArrayOf(1f, 0.82f, 0.3f, 1f)
internal val T2_RAG2 = floatArrayOf(0.35f, 0.85f, 0.8f, 1f)
internal val T2_KU = floatArrayOf(0.52f, 0.92f, 0.9f, 1f)
internal val T2_LIGASE = floatArrayOf(0.86f, 0.96f, 0.56f, 1f)
internal val T2_TUBULIN = floatArrayOf(0.5f, 0.88f, 0.66f, 1f)
internal val T2_TUBULIN_B = floatArrayOf(0.38f, 0.74f, 0.56f, 1f)
internal val T2_DYNACTIN = floatArrayOf(0.6f, 0.6f, 0.95f, 1f)
internal val T2_ADAPTOR = floatArrayOf(0.8f, 0.7f, 1f, 1f)
internal val T2_GTP_CAP = floatArrayOf(0.95f, 1f, 0.75f, 1f)
internal val T2_GTURC = floatArrayOf(0.62f, 0.62f, 0.95f, 1f)
internal val T2_DYNEIN = floatArrayOf(0.45f, 0.6f, 1f, 1f)
internal val T2_LYSOSOME = floatArrayOf(0.9f, 0.48f, 0.6f, 1f)
internal val T2_LYSO_CORE = floatArrayOf(0.66f, 0.32f, 0.44f, 1f)
internal val T2_ER = floatArrayOf(0.62f, 0.66f, 0.95f, 1f)
internal val T2_RIBO_60S = floatArrayOf(0.36f, 0.64f, 0.78f, 1f)
internal val T2_RIBO_40S = floatArrayOf(0.5f, 0.82f, 0.74f, 1f)
internal val T2_GOLGI_CIS = floatArrayOf(0.86f, 0.78f, 0.42f, 1f)
internal val T2_GOLGI_TRANS = floatArrayOf(0.98f, 0.62f, 0.3f, 1f)
internal val T2_NPC = floatArrayOf(0.7f, 0.7f, 0.92f, 1f)
internal val T2_NPC_CYTO = floatArrayOf(0.82f, 0.78f, 0.98f, 1f)
internal val T2_ENVELOPE = floatArrayOf(0.54f, 0.58f, 0.86f, 1f)
internal val T2_POL2 = floatArrayOf(0.35f, 0.55f, 0.95f, 1f)
internal val T2_MRNA = floatArrayOf(0.98f, 0.42f, 0.72f, 1f)
internal val T2_SEC61_RIM = floatArrayOf(0.85f, 0.85f, 1f, 1f)
internal val T2_SEC61 = floatArrayOf(0.64f, 0.64f, 0.98f, 1f)
internal val T2_SIGNAL = floatArrayOf(1f, 0.96f, 0.3f, 1f)
internal val T2_SIGNAL_GLOW = floatArrayOf(1f, 1f, 0.75f, 1f)
internal val T2_EXPORT = floatArrayOf(0.95f, 0.72f, 0.95f, 1f)
internal val T2_COPII = floatArrayOf(0.95f, 0.85f, 0.4f, 1f)
internal val T2_COPII_VES = floatArrayOf(0.7f, 0.8f, 1f, 1f)
internal val T2_CHAIN_A = floatArrayOf(1f, 0.74f, 0.42f, 1f)
internal val T2_CHAIN_B = floatArrayOf(0.5f, 0.9f, 0.8f, 1f)
internal val T2_BIP = floatArrayOf(0.65f, 0.95f, 0.55f, 1f)
internal val T2_GRANULE = floatArrayOf(0.8f, 0.92f, 0.96f, 1f)
internal val T2_INSULIN = floatArrayOf(0.96f, 0.88f, 0.5f, 1f)
internal val T2_PM = floatArrayOf(0.62f, 0.82f, 0.96f, 1f)
internal val T2_LIPID_HEAD = floatArrayOf(0.86f, 0.66f, 0.44f, 1f)
internal val T2_LIPID_TAIL = floatArrayOf(0.5f, 0.86f, 0.8f, 1f)
internal val T2_C_RING = floatArrayOf(1f, 0.95f, 0.62f, 1f)
internal val T2_C_MARK = floatArrayOf(1f, 0.7f, 0.4f, 1f)
internal val T2_GAMMA = floatArrayOf(0.98f, 0.96f, 0.76f, 1f)
internal val T2_ALPHA = floatArrayOf(0.56f, 0.9f, 0.86f, 1f)
internal val T2_BETA = floatArrayOf(0.36f, 0.7f, 0.76f, 1f)
internal val T2_STATOR = floatArrayOf(0.66f, 0.68f, 0.86f, 1f)
internal val T2_SUB_A = floatArrayOf(0.58f, 0.6f, 0.76f, 1f)
internal val T2_PROTON = floatArrayOf(1f, 0.9f, 0.3f, 1f)
internal val T2_PHOSPHATE = floatArrayOf(1f, 0.82f, 0.28f, 1f)
internal val T2_ADENINE = floatArrayOf(0.6f, 0.8f, 1f, 1f)
internal val T2_CELL = floatArrayOf(0.62f, 0.86f, 0.92f, 1f)
internal val T2_CHROMATID = floatArrayOf(0.62f, 0.4f, 0.92f, 1f)
internal val T2_SPINDLE = floatArrayOf(0.8f, 0.96f, 0.9f, 1f)
internal val T2_CENTROSOME = floatArrayOf(1f, 0.85f, 0.5f, 1f)
internal val T2_ACTOMYOSIN = floatArrayOf(0.95f, 0.72f, 0.66f, 1f)
internal val T2_EPI_NUC = floatArrayOf(0.45f, 0.32f, 0.7f, 1f)
internal val T2_PANETH = floatArrayOf(0.9f, 0.62f, 0.62f, 1f)
internal val T2_PANETH_GRAN = floatArrayOf(1f, 0.55f, 0.35f, 1f)
internal val T2_EPI_BORDER = floatArrayOf(0.78f, 0.58f, 0.58f, 1f)
internal val T2_EPITHELIUM_TOP = floatArrayOf(0.96f, 0.78f, 0.76f, 1f)
internal val T2_EPITHELIUM = floatArrayOf(0.92f, 0.7f, 0.7f, 1f)

// =============================================================================== toolkit

/** A small 3-vector for building geometry (build time only; per-frame code works in floats). */
private class T2V(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: T2V) = T2V(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: T2V) = T2V(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = T2V(x * k, y * k, z * k)
    infix fun dot(o: T2V) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: T2V) = T2V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun unit(): T2V { val l = len(); return if (l < 1e-9f) T2V(0f, 1f, 0f) else T2V(x / l, y / l, z / l) }
}

private fun t2v(x: Float, y: Float, z: Float) = T2V(x, y, z)
private fun t2perp(n: T2V): T2V = (if (abs(n.y) < 0.9f) n cross T2V(0f, 1f, 0f) else n cross T2V(1f, 0f, 0f)).unit()
private fun t2sm(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
private fun t2mix(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t)
private const val T2PI = PI.toFloat()

/**
 * Accumulates triangles in a local frame (x = side, y = up, z = along the rail, deeper +) with a
 * normal and a colour per vertex, and exports them either with lighting baked in (for the
 * vertex-colour shader: many colours, one draw call) or as position + normal (for the lit shader).
 */
private class T2Geo {
    var d = FloatArray(1 shl 16); var n = 0
    fun room(k: Int) { if (n + k > d.size) d = d.copyOf(max(d.size * 2, n + k)) }
    fun vtx(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: FloatArray, a: Float) {
        room(10)
        d[n] = x; d[n + 1] = y; d[n + 2] = z; d[n + 3] = nx; d[n + 4] = ny; d[n + 5] = nz
        d[n + 6] = c[0]; d[n + 7] = c[1]; d[n + 8] = c[2]; d[n + 9] = c[3] * a; n += 10
    }
    fun tri(a: T2V, b: T2V, c: T2V, col: FloatArray, al: Float = 1f) {
        val nr = (b - a) cross (c - a)
        vtx(a.x, a.y, a.z, nr.x, nr.y, nr.z, col, al); vtx(b.x, b.y, b.z, nr.x, nr.y, nr.z, col, al); vtx(c.x, c.y, c.z, nr.x, nr.y, nr.z, col, al)
    }
    fun quad(a: T2V, b: T2V, c: T2V, e: T2V, col: FloatArray, al: Float = 1f) { tri(a, b, c, col, al); tri(a, c, e, col, al) }

    /** A (nu+1) x (nv+1) grid of points (xyz), normals from the grid, colour [col] or per row [rowCol]. */
    fun grid(nu: Int, nv: Int, P: FloatArray, col: FloatArray, al: Float = 1f, rowCol: ((Int) -> FloatArray)? = null) {
        val w = nv + 1
        val N = FloatArray(P.size)
        for (i in 0..nu) for (j in 0..nv) {
            val a0 = (max(i - 1, 0) * w + j) * 3; val a1 = (min(i + 1, nu) * w + j) * 3
            val b0 = (i * w + max(j - 1, 0)) * 3; val b1 = (i * w + min(j + 1, nv)) * 3
            val ux = P[a1] - P[a0]; val uy = P[a1 + 1] - P[a0 + 1]; val uz = P[a1 + 2] - P[a0 + 2]
            val vx = P[b1] - P[b0]; val vy = P[b1 + 1] - P[b0 + 1]; val vz = P[b1 + 2] - P[b0 + 2]
            val k = (i * w + j) * 3
            N[k] = uy * vz - uz * vy; N[k + 1] = uz * vx - ux * vz; N[k + 2] = ux * vy - uy * vx
        }
        for (i in 0..nu) for (j in 0..nv) {
            val k = (i * w + j) * 3
            if (N[k] * N[k] + N[k + 1] * N[k + 1] + N[k + 2] * N[k + 2] < 1e-16f) {
                val ii = if (i == 0) min(1, nu) else if (i == nu) max(nu - 1, 0) else i
                val kk = (ii * w + j) * 3
                N[k] = N[kk]; N[k + 1] = N[kk + 1]; N[k + 2] = N[kk + 2]
            }
        }
        for (i in 0 until nu) {
            val c0 = rowCol?.invoke(i) ?: col; val c1 = rowCol?.invoke(i + 1) ?: col
            for (j in 0 until nv) {
                val p00 = i * w + j; val p10 = (i + 1) * w + j; val p11 = (i + 1) * w + j + 1; val p01 = i * w + j + 1
                emit(P, N, p00, c0, al); emit(P, N, p10, c1, al); emit(P, N, p11, c1, al)
                emit(P, N, p00, c0, al); emit(P, N, p11, c1, al); emit(P, N, p01, c0, al)
            }
        }
    }
    private fun emit(P: FloatArray, N: FloatArray, idx: Int, c: FloatArray, al: Float) {
        val k = idx * 3; vtx(P[k], P[k + 1], P[k + 2], N[k], N[k + 1], N[k + 2], c, al)
    }

    /** Parametric surface f(u, v) sampled on an nu x nv grid. */
    fun surf(nu: Int, nv: Int, col: FloatArray, al: Float = 1f, rowCol: ((Int) -> FloatArray)? = null, f: (Float, Float) -> T2V) {
        val w = nv + 1; val P = FloatArray((nu + 1) * w * 3)
        for (i in 0..nu) for (j in 0..nv) {
            val p = f(i.toFloat() / nu, j.toFloat() / nv); val k = (i * w + j) * 3
            P[k] = p.x; P[k + 1] = p.y; P[k + 2] = p.z
        }
        grid(nu, nv, P, col, al, rowCol)
    }

    /** Ellipsoid with semi-axes given as vectors. */
    fun ell(c: T2V, ax: T2V, ay: T2V, az: T2V, col: FloatArray, al: Float = 1f, nu: Int = 8, nv: Int = 12) {
        val w = nv + 1; val P = FloatArray((nu + 1) * w * 3)
        for (i in 0..nu) {
            val th = T2PI * i / nu; val cy = cos(th); val sr = sin(th)
            for (j in 0..nv) {
                val ph = 2f * T2PI * j / nv; val sx = sr * cos(ph); val sz = sr * sin(ph); val k = (i * w + j) * 3
                P[k] = c.x + ax.x * sx + ay.x * cy + az.x * sz
                P[k + 1] = c.y + ax.y * sx + ay.y * cy + az.y * sz
                P[k + 2] = c.z + ax.z * sx + ay.z * cy + az.z * sz
            }
        }
        grid(nu, nv, P, col, al)
    }
    fun ball(c: T2V, r: Float, col: FloatArray, al: Float = 1f, nu: Int = 6, nv: Int = 9) =
        ell(c, T2V(r, 0f, 0f), T2V(0f, r, 0f), T2V(0f, 0f, r), col, al, nu, nv)
    /** Axis-aligned-radii ellipsoid oriented by an axis [ay] (unit) and a hint for the x axis. */
    fun ellAxis(c: T2V, axis: T2V, ra: Float, rAxis: Float, rb: Float, col: FloatArray, al: Float = 1f, nu: Int = 8, nv: Int = 12, hint: T2V? = null) {
        val y = axis.unit(); val x = (hint?.let { (it - y * (it dot y)).unit() } ?: t2perp(y)); val z = x cross y
        ell(c, x * ra, y * rAxis, z * rb, col, al, nu, nv)
    }

    /** A tube along a polyline, radius rad(t) (t = 0..1 along it), optional rounded caps. */
    fun path(pts: List<T2V>, rad: (Float) -> Float, col: FloatArray, al: Float = 1f, sides: Int = 7, caps: Boolean = true, rowCol: ((Float) -> FloatArray)? = null, up: T2V? = null) {
        val m = pts.size; if (m < 2) return
        val T = Array(m) { k -> (pts[min(k + 1, m - 1)] - pts[max(k - 1, 0)]).unit() }
        val nor = arrayOfNulls<T2V>(m)
        nor[0] = up?.let { (it - T[0] * (it dot T[0])).let { q -> if (q.len() < 1e-5f) t2perp(T[0]) else q.unit() } } ?: t2perp(T[0])
        for (k in 1 until m) {
            val p = nor[k - 1]!!; val q = p - T[k] * (p dot T[k])
            nor[k] = if (q.len() < 1e-6f) t2perp(T[k]) else q.unit()
        }
        val cen = ArrayList<T2V>(); val tan = ArrayList<T2V>(); val nn = ArrayList<T2V>(); val rr = ArrayList<Float>(); val tt = ArrayList<Float>()
        fun ring(c: T2V, t: T2V, q: T2V, r: Float, u: Float) { cen.add(c); tan.add(t); nn.add(q); rr.add(r); tt.add(u) }
        val r0 = rad(0f); val r1 = rad(1f)
        if (caps) { ring(pts[0] - T[0] * r0, T[0], nor[0]!!, 0f, 0f); ring(pts[0] - T[0] * (r0 * 0.7f), T[0], nor[0]!!, r0 * 0.71f, 0f) }
        for (k in 0 until m) { val u = k.toFloat() / (m - 1); ring(pts[k], T[k], nor[k]!!, rad(u), u) }
        if (caps) { ring(pts[m - 1] + T[m - 1] * (r1 * 0.7f), T[m - 1], nor[m - 1]!!, r1 * 0.71f, 1f); ring(pts[m - 1] + T[m - 1] * r1, T[m - 1], nor[m - 1]!!, 0f, 1f) }
        val nu = cen.size - 1; val w = sides + 1; val P = FloatArray((nu + 1) * w * 3)
        for (i in 0..nu) {
            val c = cen[i]; val q = nn[i]; val b = tan[i] cross q; val r = rr[i]
            for (j in 0..sides) {
                val a = 2f * T2PI * j / sides; val ca = cos(a) * r; val sa = sin(a) * r; val k = (i * w + j) * 3
                P[k] = c.x + q.x * ca + b.x * sa; P[k + 1] = c.y + q.y * ca + b.y * sa; P[k + 2] = c.z + q.z * ca + b.z * sa
            }
        }
        grid(nu, sides, P, col, al, rowCol?.let { f -> { i: Int -> f(tt[i]) } })
    }
    fun tube(a: T2V, b: T2V, r0: Float, r1: Float, col: FloatArray, al: Float = 1f, sides: Int = 7, caps: Boolean = true) =
        path(listOf(a, b), { t -> r0 + (r1 - r0) * t }, col, al, sides, caps)

    /** Torus (or an arc of one) about [nrm] through [c]. */
    fun torus(c: T2V, nrm: T2V, R: Float, r: Float, col: FloatArray, al: Float = 1f, nu: Int = 24, nv: Int = 7, a0: Float = 0f, a1: Float = 2f * T2PI, e1In: T2V? = null) {
        val n = nrm.unit(); val e1 = e1In?.let { (it - n * (it dot n)).unit() } ?: t2perp(n); val e2 = n cross e1
        surf(nu, nv, col, al) { u, v ->
            val a = a0 + (a1 - a0) * u; val b = 2f * T2PI * v
            val dir = e1 * cos(a) + e2 * sin(a)
            c + dir * (R + r * cos(b)) + n * (r * sin(b))
        }
    }

    /** Flat disc facing [nrm]. */
    fun disc(c: T2V, nrm: T2V, r: Float, col: FloatArray, al: Float = 1f, seg: Int = 14, e1In: T2V? = null, rb: Float = r) {
        val n = nrm.unit(); val e1 = e1In?.let { (it - n * (it dot n)).unit() } ?: t2perp(n); val e2 = n cross e1
        for (k in 0 until seg) {
            val a0 = 2f * T2PI * k / seg; val a1 = 2f * T2PI * (k + 1) / seg
            tri(c, c + e1 * (cos(a0) * r) + e2 * (sin(a0) * rb), c + e1 * (cos(a1) * r) + e2 * (sin(a1) * rb), col, al)
        }
    }

    /** A low mucosal fold (half-ellipse section: half-width [hw], height [ht]) along [pts] on a wall whose axis passes [axis] (along z). */
    fun foldAlong(pts: List<T2V>, axis: T2V, hw: Float, ht: Float, col: FloatArray) {
        val m = pts.size
        surf(m - 1, 8, col) { u, v ->
            val f = u * (m - 1); val k = min(f.toInt(), m - 2); val t = f - k
            val p = pts[k] + (pts[k + 1] - pts[k]) * t; val tg = (pts[k + 1] - pts[k]).unit()
            val out = t2v(p.x - axis.x, p.y - axis.y, 0f).unit()
            val side = (tg cross out).unit(); val vv = v * 2f - 1f
            p + side * (hw * vv) - out * (ht * sqrt(max(0f, 1f - vv * vv)) - 0.05f)
        }
    }

    /** Multiply the colour of every vertex passing [pred] (x, y, z) by (r, g, b). */
    fun tint(pred: (Float, Float, Float) -> Boolean, r: Float, gg: Float, b: Float) {
        var v = 0
        while (v < n) { if (pred(d[v], d[v + 1], d[v + 2])) { d[v + 6] = min(1f, d[v + 6] * r); d[v + 7] = min(1f, d[v + 7] * gg); d[v + 8] = min(1f, d[v + 8] * b) }; v += 10 }
    }

    /** Copy [src]'s triangles whose centroid passes [keep]. */
    fun appendWhere(src: T2Geo, keep: (Float, Float, Float) -> Boolean) {
        val tris = src.n / 30
        for (t in 0 until tris) {
            val o = t * 30
            val cx = (src.d[o] + src.d[o + 10] + src.d[o + 20]) / 3f; val cy = (src.d[o + 1] + src.d[o + 11] + src.d[o + 21]) / 3f; val cz = (src.d[o + 2] + src.d[o + 12] + src.d[o + 22]) / 3f
            if (!keep(cx, cy, cz)) continue
            room(30); System.arraycopy(src.d, o, d, n, 30); n += 30
        }
    }

    /** A box centred at [c] with half-extent vectors [a], [b], [e]. */
    fun box(c: T2V, a: T2V, b: T2V, e: T2V, col: FloatArray, al: Float = 1f) {
        val p = Array(8) { k -> c + a * (if (k and 1 == 0) -1f else 1f) + b * (if (k and 2 == 0) -1f else 1f) + e * (if (k and 4 == 0) -1f else 1f) }
        quad(p[0], p[1], p[3], p[2], col, al); quad(p[4], p[5], p[7], p[6], col, al)
        quad(p[0], p[1], p[5], p[4], col, al); quad(p[2], p[3], p[7], p[6], col, al)
        quad(p[0], p[2], p[6], p[4], col, al); quad(p[1], p[3], p[7], p[5], col, al)
    }

    /** Closed cylinder (a disc with thickness) about [nrm]. */
    fun slab(c: T2V, nrm: T2V, r: Float, h: Float, col: FloatArray, al: Float = 1f, seg: Int = 14) {
        val n = nrm.unit()
        tube(c - n * (h * 0.5f), c + n * (h * 0.5f), r, r, col, al, seg, false)
        disc(c - n * (h * 0.5f), n, r, col, al, seg); disc(c + n * (h * 0.5f), n, r, col, al, seg)
    }

    /** Vertex-colour data (7 floats per vertex) with a key light baked in; positions mapped by [xf]. */
    fun baked(xf: ((FloatArray, Int) -> Unit)? = null): FloatArray {
        val cnt = n / 10; val out = FloatArray(cnt * 7)
        val lx = 0.30f; val ly = 0.83f; val lz = -0.47f
        for (v in 0 until cnt) {
            val s = v * 10; val o = v * 7
            val nx = d[s + 3]; val ny = d[s + 4]; val nz = d[s + 5]
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            val dd = if (l > 1e-12f) abs(nx * lx + ny * ly + nz * lz) / l else 0.6f
            val sh = 0.44f + 0.62f * dd
            out[o] = d[s]; out[o + 1] = d[s + 1]; out[o + 2] = d[s + 2]
            out[o + 3] = min(1f, d[s + 6] * sh); out[o + 4] = min(1f, d[s + 7] * sh); out[o + 5] = min(1f, d[s + 8] * sh); out[o + 6] = d[s + 9]
            if (xf != null) xf(out, o)
        }
        return out
    }

    /** Vertex colours with a gentle key light that keeps saturated colours saturated. */
    fun bakedBright(): FloatArray {
        val cnt = n / 10; val out = FloatArray(cnt * 7)
        for (v in 0 until cnt) {
            val s = v * 10; val o = v * 7
            val nx = d[s + 3]; val ny = d[s + 4]; val nz = d[s + 5]; val l = sqrt(nx * nx + ny * ny + nz * nz)
            val dd = if (l > 1e-12f) abs(nx * 0.3f + ny * 0.83f - nz * 0.47f) / l else 0.6f
            val sh = 0.72f + 0.38f * dd
            out[o] = d[s]; out[o + 1] = d[s + 1]; out[o + 2] = d[s + 2]
            out[o + 3] = min(1f, d[s + 6] * sh); out[o + 4] = min(1f, d[s + 7] * sh); out[o + 5] = min(1f, d[s + 8] * sh); out[o + 6] = d[s + 9]
        }
        return out
    }

    /** Position + unit normal (6 floats per vertex) for the lit shader. */
    fun lit(): FloatArray {
        val cnt = n / 10; val out = FloatArray(cnt * 6)
        for (v in 0 until cnt) {
            val s = v * 10; val o = v * 6
            val nx = d[s + 3]; val ny = d[s + 4]; val nz = d[s + 5]
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            out[o] = d[s]; out[o + 1] = d[s + 1]; out[o + 2] = d[s + 2]
            out[o + 3] = nx / l; out[o + 4] = ny / l; out[o + 5] = nz / l
        }
        return out
    }
}

/** A baked triangle mesh for the lit shader (single colour per draw), drawn double-sided. */
private class T2Lit(data: FloatArray) : LitMesh() {
    val vbo = makeVbo(data)
    val count = data.size / 6
    override fun draw(positionHandle: Int, normalHandle: Int) {
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}

// Meshes are built lazily on the GL thread, per tour (the rail differs), and rebuilt if the GL
// context was recreated (the renderer's own sphere mesh is then a new object).
private val t2Cache = HashMap<String, Any>()
private var t2Owner: Any? = null

@Suppress("UNCHECKED_CAST")
private fun <T : Any> StereoBodyRenderer.t2Get(key: String, build: () -> T): T {
    if (t2Owner !== sphere) { t2Cache.clear(); t2Owner = sphere }
    val k = "$key#${map.id}"
    val hit = t2Cache[k]
    if (hit != null) return hit as T
    val made = build()
    t2Cache[k] = made
    return made
}

/**
 * The rail around one stop, tabulated by arc length (units along the rail from the node):
 * centre, direction, side, up and the rail parameter p, 13 floats per sample.
 */
private class T2Rail(val aMin: Float, val step: Float, val f: FloatArray) {
    val cnt = f.size / 13
    fun at(a: Float, o: FloatArray) {
        var t = (a - aMin) / step; var ex = 0f
        if (t < 0f) { ex = t * step; t = 0f } else if (t > cnt - 1f) { ex = (t - (cnt - 1f)) * step; t = cnt - 1f }
        val k = min(t.toInt(), cnt - 2); val w = t - k; val b0 = k * 13; val b1 = b0 + 13
        for (q in 0 until 13) o[q] = f[b0 + q] + (f[b1 + q] - f[b0 + q]) * w
        if (ex != 0f) { o[0] += o[3] * ex; o[1] += o[4] * ex; o[2] += o[5] * ex }
    }
}

private fun StereoBodyRenderer.t2Rail(i: Int): T2Rail = t2Get("rail$i") {
    val last = nodes.lastIndex.toFloat()
    val p0 = max(0f, i - 1.7f); val p1 = min(last, i + 1.7f)
    val dp = 0.002f; val m = ((p1 - p0) / dp).toInt() + 1
    val P = FloatArray(m); val L = FloatArray(m); val q = FloatArray(3)
    var px = 0f; var py = 0f; var pz = 0f
    for (k in 0 until m) {
        P[k] = min(p1, p0 + k * dp); curvePoint(P[k], q)
        if (k > 0) L[k] = L[k - 1] + sqrt((q[0] - px) * (q[0] - px) + (q[1] - py) * (q[1] - py) + (q[2] - pz) * (q[2] - pz))
        px = q[0]; py = q[1]; pz = q[2]
    }
    val ki = ((i - p0) / dp).coerceIn(0f, (m - 1).toFloat()); val kk = min(ki.toInt(), max(m - 2, 0))
    val li = L[kk] + (L[min(kk + 1, m - 1)] - L[kk]) * (ki - kk)
    val aMin = -64f; val step = 0.25f; val cnt = 513
    val out = FloatArray(cnt * 13)
    var seek = 0
    for (j in 0 until cnt) {
        val s = aMin + j * step + li
        var ex = 0f; val p: Float
        if (s <= L[0]) { p = P[0]; ex = s - L[0] }
        else if (s >= L[m - 1]) { p = P[m - 1]; ex = s - L[m - 1] }
        else {
            while (seek < m - 2 && L[seek + 1] < s) seek++
            p = P[seek] + dp * (s - L[seek]) / max(1e-6f, L[seek + 1] - L[seek])
        }
        val fr = frameAt(p)
        val o = j * 13
        out[o] = fr.cx + fr.dx * ex; out[o + 1] = fr.cy + fr.dy * ex; out[o + 2] = fr.cz + fr.dz * ex
        out[o + 3] = fr.dx; out[o + 4] = fr.dy; out[o + 5] = fr.dz
        out[o + 6] = fr.sx; out[o + 7] = fr.sy; out[o + 8] = fr.sz
        out[o + 9] = fr.ux; out[o + 10] = fr.uy; out[o + 11] = fr.uz
        out[o + 12] = p
    }
    T2Rail(aMin, step, out)
}

/**
 * Maps local (x side, y up, z along) to world: near the rail (radius < [near]) the geometry
 * follows the rail's curve; beyond [far] it is rigid in the node's frame (big structures keep
 * their shape); in between it blends. far <= 0 means rigid everywhere.
 */
private fun StereoBodyRenderer.t2Bend(i: Int, near: Float, far: Float): (FloatArray, Int) -> Unit {
    val rail = t2Rail(i); val f0 = FloatArray(13); rail.at(0f, f0); val fr = FloatArray(13)
    return { o, k ->
        val x = o[k]; val y = o[k + 1]; val z = o[k + 2]
        var wx = f0[0] + f0[3] * z + f0[6] * x + f0[9] * y
        var wy = f0[1] + f0[4] * z + f0[7] * x + f0[10] * y
        var wz = f0[2] + f0[5] * z + f0[8] * x + f0[11] * y
        if (far > 0f) {
            val w = 1f - t2sm((sqrt(x * x + y * y) - near) / max(1e-3f, far - near))
            if (w > 0f) {
                rail.at(z, fr)
                wx += (fr[0] + fr[6] * x + fr[9] * y - wx) * w
                wy += (fr[1] + fr[7] * x + fr[10] * y - wy) * w
                wz += (fr[2] + fr[8] * x + fr[11] * y - wz) * w
            }
        }
        o[k] = wx; o[k + 1] = wy; o[k + 2] = wz
    }
}

private fun StereoBodyRenderer.t2Bake(g: T2Geo, i: Int, near: Float = 3f, far: Float = 9f) = TriMesh(g.baked(t2Bend(i, near, far)))

private val t2F = FloatArray(13)
private val t2G = FloatArray(13)
private val t2Q = FloatArray(3)

/** Rail frame at [a] units from stop [i] (bent with the rail), into [out]. */
private fun StereoBodyRenderer.t2Frame(i: Int, a: Float, out: FloatArray = t2F): FloatArray { t2Rail(i).at(a, out); return out }
/** The node's own (rigid) frame, displaced [a] along its axis. */
private fun StereoBodyRenderer.t2Rigid(i: Int, a: Float, out: FloatArray = t2G): FloatArray {
    t2Rail(i).at(0f, out); out[0] += out[3] * a; out[1] += out[4] * a; out[2] += out[5] * a; return out
}
/** World position of local (x, y) in frame [fr] (plus [z] along the frame's axis), into t2Q. */
private fun t2W(fr: FloatArray, x: Float, y: Float, z: Float = 0f): FloatArray {
    t2Q[0] = fr[0] + fr[6] * x + fr[9] * y + fr[3] * z
    t2Q[1] = fr[1] + fr[7] * x + fr[10] * y + fr[4] * z
    t2Q[2] = fr[2] + fr[8] * x + fr[11] * y + fr[5] * z
    return t2Q
}

/** model = frame [fr] (x side, y up, z along) at local (x, y, z), turned [rot] radians about up (+x toward +z). */
private fun StereoBodyRenderer.t2Model(fr: FloatArray, x: Float, y: Float, z: Float = 0f, rot: Float = 0f) {
    val c = cos(rot); val s = sin(rot)
    model[0] = fr[6] * c + fr[3] * s; model[1] = fr[7] * c + fr[4] * s; model[2] = fr[8] * c + fr[5] * s; model[3] = 0f
    model[4] = fr[9]; model[5] = fr[10]; model[6] = fr[11]; model[7] = 0f
    model[8] = fr[3] * c - fr[6] * s; model[9] = fr[4] * c - fr[7] * s; model[10] = fr[5] * c - fr[8] * s; model[11] = 0f
    model[12] = fr[0] + fr[6] * x + fr[9] * y + fr[3] * z
    model[13] = fr[1] + fr[7] * x + fr[10] * y + fr[4] * z
    model[14] = fr[2] + fr[8] * x + fr[11] * y + fr[5] * z
    model[15] = 1f
}

/** Draw a vertex-colour mesh with the current model matrix (double-sided). */
private fun StereoBodyRenderer.t2Draw(m: ColorVboMesh, translucent: Boolean = false, size: Float = 1f, points: Boolean = false) {
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, size, points)
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (translucent) GLES20.glDepthMask(false)
    m.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}
private fun StereoBodyRenderer.t2DrawWorld(m: ColorVboMesh, translucent: Boolean = false, size: Float = 1f, points: Boolean = false) {
    Matrix.setIdentityM(model, 0); t2Draw(m, translucent, size, points)
}

/** Draw a lit mesh with the current model matrix (double-sided; translucent when alpha < 1). */
private fun StereoBodyRenderer.t2DrawLit(mesh: LitMesh, base: FloatArray, accent: FloatArray, alpha: Float = 1f, glow: Float = 0f, pattern: Float = 0f) {
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (alpha < 0.99f) GLES20.glDepthMask(false)
    drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}

/** An ellipsoid at local (x, y) in frame [fr]: radii rx (side), ry (up), rz (along). */
private fun StereoBodyRenderer.t2Blob(fr: FloatArray, x: Float, y: Float, rx: Float, ry: Float, rz: Float, base: FloatArray, accent: FloatArray = COL_LAMP, alpha: Float = 1f, glow: Float = 0f, dz: Float = 0f) {
    val p = t2W(fr, x, y, dz)
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (alpha < 0.99f) GLES20.glDepthMask(false)
    drawBasis(p[0], p[1], p[2], fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], rx, ry, rz, sphere, base, accent, alpha, 0f, glow)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}

/** Oriented lit mesh: local z along (zx,zy,zz), local y toward (yx,yy,yz). */
private fun StereoBodyRenderer.t2Basis(p: FloatArray, zx: Float, zy: Float, zz: Float, yx: Float, yy: Float, yz: Float, sx: Float, sy: Float, sz: Float,
                                       mesh: LitMesh, base: FloatArray, accent: FloatArray = COL_LAMP, alpha: Float = 1f, glow: Float = 0f) {
    val px = p[0]; val py = p[1]; val pz = p[2]
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (alpha < 0.99f) GLES20.glDepthMask(false)
    drawBasis(px, py, pz, zx, zy, zz, yx, yy, yz, sx, sy, sz, mesh, base, accent, alpha, 0f, glow)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}

/**
 * While stop [i] is the current one, its anatomy may be far bigger than the passage: clear depth
 * so it draws over the wall, then write the wall's depth back so later drawing (drift, the craft,
 * the next stop's landmark) is still occluded by the passage. Otherwise the wall hides it as usual.
 */
private inline fun StereoBodyRenderer.t2Open(i: Int, seconds: Float, body: (Boolean) -> Unit) {
    val own = abs(routeProgress - i) < 0.5f
    if (own) { GLES20.glDepthMask(true); GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT) }
    body(own)
    if (own) {
        GLES20.glColorMask(false, false, false, false)
        drawTunnel(seconds)
        GLES20.glColorMask(true, true, true, true)
    }
}

/**
 * This tour's scenes show only around their own stop: beyond 0.85 of a stop the passage ahead is
 * left clear instead of hanging the next stop's anatomy down it. Fades the landmark in over 0.3.
 */
private fun StereoBodyRenderer.t2Near(i: Int): Boolean {
    val d = abs(routeProgress - i)
    if (d > 0.85f) return false
    val f = ((0.85f - d) / 0.3f).coerceIn(0f, 1f)
    landmarkFade *= f; colorShader.globalFade *= f
    return true
}

// Per-frame lines and points (world coordinates), and per-frame triangles.
private val t2Lines by lazy { DynMesh(3000) }
private var t2Ln = 0
private fun t2LinesBegin() { t2Ln = 0 }
private fun t2Vert(x: Float, y: Float, z: Float, c: FloatArray, a: Float) {
    if (t2Ln >= 3000) return
    val d = t2Lines.data; val o = t2Ln * 7
    d[o] = x; d[o + 1] = y; d[o + 2] = z; d[o + 3] = c[0]; d[o + 4] = c[1]; d[o + 5] = c[2]; d[o + 6] = a
    t2Ln++
}
private fun t2Seg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, c: FloatArray, a0: Float, a1: Float = a0) {
    if (t2Ln + 2 > 3000) return
    t2Vert(ax, ay, az, c, a0); t2Vert(bx, by, bz, c, a1)
}
private fun StereoBodyRenderer.t2LinesEnd(width: Float = 1.5f, points: Boolean = false, size: Float = 1f) {
    if (t2Ln == 0) return
    Matrix.setIdentityM(model, 0)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, size, points)
    if (!points) lineWidth(width)
    GLES20.glDepthMask(false)
    t2Lines.draw(colorShader.positionHandle, colorShader.colorHandle, if (points) GLES20.GL_POINTS else GLES20.GL_LINES, t2Ln)
    GLES20.glDepthMask(true)
    lineWidth(1f)
    t2Ln = 0
}

private val t2Tris by lazy { DynMesh(4000) }
private val t2ChromTris by lazy { DynMesh(36000) }

/** A parametric surface baked as one lit mesh (one draw call, unlike ParamMesh's one per stack). */
private fun t2LitSurf(nu: Int, nv: Int, f: (Float, Float, FloatArray) -> Unit): T2Lit {
    val g = T2Geo(); val o = FloatArray(3)
    g.surf(nu, nv, T2_ENAMEL) { u, v -> f(u, v, o); T2V(o[0], o[1], o[2]) }
    return T2Lit(g.lit())
}

/** A unit sphere as a lit mesh, double-sided (for cells seen from inside). */
private fun StereoBodyRenderer.t2BallMesh(): LitMesh = t2Get("ballmesh") {
    t2LitSurf(14, 22) { u, v, out ->
        val th = u * T2PI; val ph = v * 2f * T2PI
        out[0] = sin(th) * cos(ph); out[1] = cos(th); out[2] = sin(th) * sin(ph)
    }
}

/** Wall radius of the passage at rail parameter p and angle ang (from side toward up), as buildTunnel makes it. */
private fun StereoBodyRenderer.t2WallR(p: Float, ang: Float): Float {
    val step = 0.08f; val segs = 14
    val kr = max(0f, p) / step; val r0 = floor(kr); val fr = kr - r0
    var a = ang % (2f * T2PI); if (a < 0f) a += 2f * T2PI
    val ka = a / (2f * T2PI) * segs; val a0 = floor(ka); val fa = ka - a0
    val k0 = a0.toInt() % segs; val k1 = (k0 + 1) % segs
    val pa = r0 * step; val pb = pa + step
    fun bump(k: Int, pp: Float) = 1f + 0.07f * sin(k * 3.1f + pp * 9.3f) + 0.04f * sin(k * 7.7f + pp * 21f)
    val ra = tunnelRadius(pa); val rb = tunnelRadius(pb)
    val b00 = bump(k0, pa) * ra; val b10 = bump(k1, pa) * ra; val b01 = bump(k0, pb) * rb; val b11 = bump(k1, pb) * rb
    return (b00 * (1f - fa) + b10 * fa) * (1f - fr) + (b01 * (1f - fa) + b11 * fa) * fr
}

// ============================================================================ stop 0: MOUTH
// 8 mm a unit (the Mote is 12 mm). The craft sits in the gape between the incisors; ahead lie
// the oral cavity (tongue below, palate above, the dental arches either side), the fauces and
// uvula, and beyond them the epiglottis over the laryngeal inlet.

private const val T2_INC = 0
private const val T2_CAN = 1
private const val T2_PREM = 2
private const val T2_MOL = 3

/** One crown: mesiodistal width, labiolingual depth, crown height (units), class. */
private class T2Tooth(val w: Float, val t: Float, val h: Float, val kind: Int)

// Midline to second molar. Upper central incisor 8.5 x 7 x 10.5 mm, first molar 10 x 11 x 7.5 mm, etc.
private val T2_UPPER = listOf(
    T2Tooth(1.06f, 0.88f, 1.30f, T2_INC), T2Tooth(0.82f, 0.76f, 1.12f, T2_INC), T2Tooth(0.95f, 1.0f, 1.32f, T2_CAN),
    T2Tooth(0.88f, 1.12f, 1.03f, T2_PREM), T2Tooth(0.84f, 1.1f, 0.98f, T2_PREM), T2Tooth(1.28f, 1.4f, 0.92f, T2_MOL), T2Tooth(1.12f, 1.35f, 0.88f, T2_MOL)
)
private val T2_LOWER = listOf(
    T2Tooth(0.68f, 0.74f, 1.12f, T2_INC), T2Tooth(0.74f, 0.78f, 1.18f, T2_INC), T2Tooth(0.86f, 0.95f, 1.37f, T2_CAN),
    T2Tooth(0.88f, 0.96f, 1.05f, T2_PREM), T2Tooth(0.9f, 1.0f, 1.0f, T2_PREM), T2Tooth(1.38f, 1.3f, 0.95f, T2_MOL), T2Tooth(1.28f, 1.25f, 0.9f, T2_MOL)
)

/** A parabolic dental arch z = z0 + k x², tabulated by arc length from the midline. */
private class T2Arch(val z0: Float, val k: Float) {
    private val xs = FloatArray(1600); private val ss = FloatArray(1600)
    init {
        var s = 0f
        for (q in 0 until 1600) {
            val x = q * 0.004f
            if (q > 0) { val xp = x - 0.004f; val dz = k * (x * x - xp * xp); s += sqrt(1.6e-5f + dz * dz) }
            xs[q] = x; ss[q] = s
        }
    }
    fun x(s: Float): Float {
        var lo = 0; var hi = 1599
        if (s >= ss[hi]) return xs[hi]
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (ss[m] < s) lo = m else hi = m }
        val t = (s - ss[lo]) / max(1e-6f, ss[hi] - ss[lo]); return xs[lo] + (xs[hi] - xs[lo]) * t
    }
    fun z(x: Float) = z0 + k * x * x
}

/** A crown standing on [base], e1 along the arch, e2 labial, e3 toward the occlusal plane. */
private fun T2Geo.crown(base: T2V, e1: T2V, e2: T2V, e3: T2V, tt: T2Tooth) {
    val W = tt.w; val T = tt.t; val H = tt.h
    val ex = when (tt.kind) { T2_MOL -> 3.4f; T2_PREM -> 2.6f; else -> 2.2f }
    fun side(hn: Float, out: FloatArray) {
        when (tt.kind) {
            // Incisor: a spade, widening toward a thin straight cutting edge.
            T2_INC -> { out[0] = W * 0.5f * (0.74f + 0.26f * hn); out[1] = T * 0.5f * (1f - 0.8f * hn.pow(1.4f)) + 0.03f }
            // Canine: the longest crown, tapering to a single pointed cusp.
            T2_CAN -> { out[0] = W * 0.5f * (0.86f + 0.14f * sin(T2PI * hn * 0.8f)) * (1f - 0.78f * t2sm((hn - 0.45f) / 0.55f)); out[1] = T * 0.5f * (1f - 0.5f * hn.pow(1.6f)) }
            // Premolars and molars: bulging, boxy crowns.
            else -> { val b = sin(T2PI * min(1f, hn * 0.9f + 0.1f)); out[0] = W * 0.5f * (0.8f + 0.2f * b); out[1] = T * 0.5f * (0.82f + 0.18f * b) }
        }
    }
    val rim = FloatArray(2); side(1f, rim)
    val cx0 = FloatArray(4); val cz0 = FloatArray(4); val ca = FloatArray(4); var nc = 0
    if (tt.kind == T2_MOL) {   // four cusps (mesio/disto, buccal/lingual)
        cx0[0] = 0.45f * rim[0]; cz0[0] = 0.42f * rim[1]; ca[0] = 1f
        cx0[1] = -0.45f * rim[0]; cz0[1] = 0.42f * rim[1]; ca[1] = 0.95f
        cx0[2] = 0.45f * rim[0]; cz0[2] = -0.42f * rim[1]; ca[2] = 0.9f
        cx0[3] = -0.45f * rim[0]; cz0[3] = -0.42f * rim[1]; ca[3] = 0.85f; nc = 4
    } else if (tt.kind == T2_PREM) {   // a buccal and a (lower) lingual cusp
        cx0[0] = 0f; cz0[0] = 0.45f * rim[1]; ca[0] = 1f
        cx0[1] = 0f; cz0[1] = -0.45f * rim[1]; ca[1] = 0.75f; nc = 2
    }
    val sig = 0.34f * min(rim[0], rim[1]).coerceAtLeast(0.05f)
    val tmp = FloatArray(2)
    surf(34, 16, T2_ENAMEL, 1f, { i -> if (i <= 6) T2_ENAMEL_NECK else T2_ENAMEL }) { u, v ->
        val ph = 2f * T2PI * v; val c = cos(ph); val s = sin(ph)
        val cx = sign(c) * abs(c).pow(2f / ex); val cz = sign(s) * abs(s).pow(2f / ex)
        val lx: Float; val lz: Float; val h: Float
        if (u <= 0.7f) {
            val hn = u / 0.7f; side(hn, tmp); lx = cx * tmp[0]; lz = cz * tmp[1]; h = H * hn
        } else {
            val t = (u - 0.7f) / 0.3f
            lx = cx * rim[0] * (1f - t); lz = cz * rim[1] * (1f - t)
            h = when (tt.kind) {
                T2_INC -> H + 0.03f * sin(T2PI * t)
                T2_CAN -> H + 0.2f * H * (1f - (1f - t) * (1f - t))
                else -> {
                    var b = 0f
                    for (q in 0 until nc) { val dx = lx - cx0[q]; val dz = lz - cz0[q]; b += ca[q] * 0.16f * H * exp(-(dx * dx + dz * dz) / (sig * sig)) }
                    H + sin(T2PI * 0.5f * t) * (b - 0.07f * H)
                }
            }
        }
        base + e1 * lx + e2 * lz + e3 * h
    }
}

private const val T2_UES_Z = 13.8f
private const val T2_PH_RX = 2.85f
private const val T2_PH_RY = 3.2f
private const val T2_PH_CY = -0.3f
/** Half-width of the oropharynx at height y (its lateral walls). */
private fun t2PharynxX(y: Float): Float = T2_PH_RX * sqrt(max(0.05f, 1f - ((y - T2_PH_CY) / T2_PH_RY).pow(2)))

/** Mid-line height of the tongue's dorsum along the mouth. */
private fun t2TongueMid(z: Float): Float = when {
    z < 3.2f -> -1.6f + 0.35f * t2sm((z - 1.35f) / 1.85f)
    z < 5.8f -> -1.25f
    else -> -1.25f - 2.05f * ((z - 5.8f) / 4.1f).coerceIn(0f, 1f).pow(1.5f)
}
private fun t2TongueHalf(z: Float): Float =
    if (z < 8f) 2.25f * min(1f, sqrt(max(0.02f, z - 1.3f) / 2f)) else 2.25f - 0.5f * ((z - 8f) / 1.9f).coerceIn(0f, 1f)
private fun t2TongueY(xn: Float, z: Float): Float {
    val hw = t2TongueHalf(z); val a = abs(xn)
    return if (a <= 1f) t2TongueMid(z) - 0.5f * a * a - (if (z < 6.6f) 0.07f * exp(-(a * hw / 0.13f) * (a * hw / 0.13f)) else 0f)
    else t2TongueMid(z) - 0.5f - 1.9f * (a - 1f) / 0.3f
}

private fun StereoBodyRenderer.t2MouthMesh(i: Int): TriMesh = t2Get("mouth$i") {
    val g = T2Geo()
    val upper = T2Arch(0.55f, 0.52f); val lower = T2Arch(0.85f, 0.58f)
    // ---- the two dental arches: 14 crowns each (incisors, canines, premolars, first and second
    //      molars), each set in a gum ridge with interdental papillae.
    for (arch in 0..1) {
        val up = arch == 0
        val tab = if (up) upper else lower
        val specs = if (up) T2_UPPER else T2_LOWER
        val vdir = if (up) -1f else 1f
        val necks = ArrayList<Pair<Float, T2V>>()      // (signed arc position, gum-line point)
        val labs = ArrayList<Pair<T2V, T2V>>()
        for (sd in SIGNS) {
            var s = 0f
            for (tt in specs) {
                val sc = s + tt.w * 0.5f; s += tt.w
                val x = tab.x(sc); val z = tab.z(x)
                val tl = t2v(sd, 0f, 2f * tab.k * x).unit()
                val lab = t2v(sd * 2f * tab.k * x, 0f, -1f).unit()
                val tilt = when (tt.kind) { T2_INC -> if (up) 0.24f else 0.12f; T2_CAN -> 0.1f; else -> 0f }
                val occ = t2v(0f, vdir, 0f) * cos(tilt) + lab * sin(tilt)
                val yOcc = if (up) 1.35f else -1.35f + 0.12f * (z - 0.85f)
                val tip = t2v(sd * x, yOcc, z)
                val base = tip - occ * tt.h
                g.crown(base, tl, lab, occ, tt)
                necks.add(sd * sc to (base - occ * 0.16f))
                labs.add(base to lab * (tt.t * 0.42f))
            }
        }
        necks.sortBy { it.first }
        val gum = ArrayList<T2V>()
        for (k in 0 until necks.size - 1) {
            val a = necks[k].second; val b = necks[k + 1].second
            for (q in 0 until 3) gum.add(a + (b - a) * (q / 3f))
            // interdental papilla between neighbouring crowns, labial and lingual
            val mid = (a + b) * 0.5f
            val tan = (b - a).unit(); val nrm = t2v(-tan.z, 0f, tan.x).unit()
            for (sgn in SIGNS) g.ellAxis(mid + nrm * (0.36f * sgn) - t2v(0f, vdir, 0f) * 0.26f, t2v(0f, 1f, 0f), 0.17f, 0.3f, 0.12f, T2_GUM, 1f, 5, 8, tan)
        }
        gum.add(necks.last().second)
        g.path(gum, { 0.56f }, T2_GUM, 1f, 9, true)
    }
    // ---- hard palate: a vault over the upper arch; transverse rugae on its front third, a median
    //      raphe and the incisive papilla behind the central incisors.
    val zF = 1.25f; val zB = 6.3f
    fun palHalf(z: Float) = max(0.45f, sqrt(max(0f, (z - upper.z0) / upper.k)) - 0.62f)
    fun palY(u: Float, z: Float): Float {
        val edge = 2.62f; val apex = 2.75f + 0.95f * t2sm((z - zF) / 2.2f)
        return edge + (apex - edge) * (1f - u * u).coerceAtLeast(0f).pow(0.6f)
    }
    g.surf(12, 16, T2_PALATE) { v, u -> val z = zF + (zB - zF) * v; val uu = u * 2f - 1f; t2v(uu * palHalf(z), palY(uu, z), z) }
    for (zr in floatArrayOf(1.62f, 2.08f, 2.54f, 3.0f)) for (sd in SIGNS) {
        val pts = ArrayList<T2V>()
        for (q in 0..7) { val xx = 0.12f + q / 7f * (palHalf(zr) * 0.78f - 0.12f); val z = zr + 0.08f * sin(xx * 4f); pts.add(t2v(sd * xx, palY(xx / palHalf(z), z) - 0.05f, z)) }
        g.path(pts, { t -> 0.075f * (1f - 0.4f * t) }, T2_RUGA, 1f, 5, true)
    }
    g.path((0..12).map { q -> val z = 1.4f + q / 12f * 4.7f; t2v(0f, palY(0f, z) - 0.03f, z) }, { 0.045f }, T2_RUGA, 1f, 5, true)
    g.ell(t2v(0f, palY(0f, 1.35f) - 0.06f, 1.42f), t2v(0.22f, 0f, 0f), t2v(0f, 0.1f, 0f), t2v(0f, 0f, 0.3f), T2_RUGA)
    // ---- the oropharynx behind the fauces: its lateral walls (an elliptic tube of mucosa), on which
    //      the palatoglossal (front) and palatopharyngeal (back) arches rise as low mucosal folds
    //      running down from the free edge of the soft palate to the side of the tongue, the
    //      palatine tonsil in the fossa between them.
    g.surf(10, 40, T2_PHARYNX) { v, u ->
        val z = 7.6f + 4.7f * v; val a = u * 2f * T2PI
        t2v(cos(a) * T2_PH_RX, T2_PH_CY + sin(a) * T2_PH_RY, z)
    }
    for (sd in SIGNS) {
        val front = listOf(t2v(sd * 1.9f, 1.9f, 8.7f)) + (0..8).map { q -> val t = q / 8f; val y = 1.85f - 3.35f * t; t2v(sd * t2PharynxX(y), y, 8.7f - 0.9f * t) }
        val back = listOf(t2v(sd * 1.9f, 1.95f, 9.1f)) + (0..8).map { q -> val t = q / 8f; val y = 1.9f - 4.5f * t; t2v(sd * t2PharynxX(y), y, 9.1f + 1.7f * t) }
        for (pl in listOf(front, back)) g.foldAlong(pl, t2v(0f, T2_PH_CY, 0f), 0.25f, 0.2f, T2_SOFT_PALATE)
        g.ell(t2v(sd * (t2PharynxX(0.15f) - 0.15f), 0.15f, 9.2f), t2v(0.45f, 0f, 0f), t2v(0f, 1.0f, 0f), t2v(0f, 0f, 0.6f), T2_TONSIL)
    }
    // ---- behind the larynx the oropharynx narrows down and back into the laryngopharynx: its
    //      posterior wall faces us behind the uvula and slopes down to the upper oesophageal
    //      sphincter, a transverse slit about 4 cm below the mouth, behind the cricoid; the
    //      swallow goes over the epiglottis and down behind the larynx into it.
    val slitY = t2RailYAtRigidZ(i, T2_UES_Z)
    g.surf(18, 40, T2_PHARYNX) { v, u ->
        val sv = t2sm(v); val z = 12.3f + (T2_UES_Z - 12.3f) * v; val a = u * 2f * T2PI
        val w = T2_PH_RX + (1.3f - T2_PH_RX) * sv; val h = T2_PH_RY + (0.9f - T2_PH_RY) * sv
        t2v(cos(a) * w, T2_PH_CY + (slitY - T2_PH_CY) * sv + sin(a) * h, z)
    }
    for (sd in SIGNS) {   // piriform recesses: gutters either side of the larynx, down to the slit
        val pts = (0..10).map { q -> val t = q / 10f; t2v(sd * (1.35f - 0.55f * t), -2.4f + (slitY + 0.3f + 2.4f) * t, 10.6f + (T2_UES_Z - 0.4f - 10.6f) * t) }
        g.foldAlong(pts, t2v(0f, -1f, 0f), 0.35f, -0.25f, T2_VALLECULA)
    }
    run {   // the upper oesophageal sphincter (cricopharyngeus), a slit-shaped rim
        val c = t2v(0f, slitY, T2_UES_Z)
        g.surf(28, 8, T2_OES_MUSCLE) { u, v ->
            val a = u * 2f * T2PI; val b = v * 2f * T2PI
            t2v(c.x + cos(a) * (1.3f + 0.2f * cos(b)), c.y + sin(a) * (0.9f + 0.2f * cos(b)), c.z + 0.2f * sin(b))
        }
    }
    // ---- the tongue filling the floor of the mouth inside the lower arch: a convex dorsum with a
    //      median groove, the V of circumvallate papillae at the back of its oral part, fungiform
    //      papillae dotted over the front, the root curving down into the pharynx.
    g.surf(18, 30, T2_TONGUE) { v, u ->
        val z = 1.3f + 8.6f * v; val xn = (u * 2f - 1f) * 1.3f; val hw = t2TongueHalf(z)
        val a = abs(xn); val x = if (a <= 1f) xn * hw else sign(xn) * hw * (1f + 0.06f * (a - 1f) / 0.3f)
        t2v(x, t2TongueY(xn, z), z)
    }
    g.ell(t2v(0f, -1.95f, 1.52f), t2v(0.95f, 0f, 0f), t2v(0f, 0.5f, 0f), t2v(0f, 0f, 0.45f), T2_TONGUE)
    for (q in -4..4) {   // circumvallate papillae: flat discs flush with the dorsum, each in its trench
        val side = q / 4f; val z = 6.6f - 0.85f * abs(side); val x = 1.45f * side
        val y = t2TongueY(x / t2TongueHalf(z), z)
        g.slab(t2v(x, y - 0.01f, z), t2v(0f, 1f, 0f), 0.13f, 0.05f, T2_PAPILLA, 1f, 12)
        g.torus(t2v(x, y - 0.03f, z), t2v(0f, 1f, 0f), 0.17f, 0.035f, T2_VALLECULA_T, 1f, 14, 5)
    }
    run {
        val rnd = java.util.Random(7L)
        repeat(46) {
            val z = 1.8f + rnd.nextFloat() * 4.2f; val xn = (rnd.nextFloat() * 2f - 1f) * 0.85f
            g.ball(t2v(xn * t2TongueHalf(z), t2TongueY(xn, z) + 0.015f, z), 0.035f, T2_FUNGIFORM, 1f, 3, 5)
        }
    }
    // valleculae: the two pits between the tongue root and the front of the epiglottis
    for (sd in SIGNS) g.ell(t2v(sd * 0.5f, t2TongueMid(9.8f) + 0.05f, 9.85f), t2v(0.5f, 0f, 0f), t2v(0f, 0.15f, 0f), t2v(0f, 0f, 0.4f), T2_VALLECULA)
    // ---- the lips: an upper lip with its cupid's bow and a lower lip, meeting at the commissures,
    //      set in the skin of the face (philtral columns above the upper lip).
    for (upLip in 0..1) {
        val pts = ArrayList<T2V>()
        for (q in 0..40) {
            val xn = q / 40f * 2f - 1f; val x = xn * 3.05f
            val env = (1f - xn * xn).coerceAtLeast(0f).pow(0.55f)
            val bow = if (upLip == 0) -0.2f * exp(-(x / 0.24f) * (x / 0.24f)) + 0.1f * exp(-((abs(x) - 0.5f) / 0.26f) * ((abs(x) - 0.5f) / 0.26f)) else 0f
            val y = if (upLip == 0) 1.95f * env + bow else -2.05f * env
            pts.add(t2v(x, y, -0.2f - 0.32f * env))
        }
        g.path(pts, { t -> 0.2f + 0.36f * sqrt(sin(T2PI * t).coerceAtLeast(0f)) }, T2_LIP, 1f, 8, true)
    }
    for (sd in SIGNS) g.ball(t2v(sd * 3.1f, 0f, -0.18f), 0.24f, T2_LIP)
    g.surf(8, 40, T2_SKIN) { t, u ->
        val a = u * 2f * T2PI; val c = cos(a); val s = sin(a)
        val rx = 3.45f + (6.8f - 3.45f) * t; val ry = (if (s > 0f) 2.45f else 2.5f) + (5.8f - 2.45f) * t
        t2v(c * rx, s * ry, -0.3f + 0.9f * t * t)
    }
    for (sd in SIGNS) g.path(listOf(t2v(sd * 0.36f, 2.3f, -0.52f), t2v(sd * 0.3f, 3.2f, -0.5f), t2v(sd * 0.27f, 4.0f, -0.42f)), { 0.11f }, T2_SKIN, 1f, 6, true)
    TriMesh(g.baked(t2Bend(i, 0f, 0f)))
}

/** Filiform papillae: the velvety pile over the front two-thirds of the tongue (points). */
private fun StereoBodyRenderer.t2Filiform(i: Int): PointMesh = t2Get("filiform$i") {
    val rnd = java.util.Random(13L); val out = ArrayList<Float>()
    repeat(420) {
        val z = 1.7f + rnd.nextFloat() * 4.6f; val xn = (rnd.nextFloat() * 2f - 1f) * 0.9f
        out.addAll(listOf(xn * t2TongueHalf(z), t2TongueY(xn, z) + 0.02f, z, T2_FILIFORM[0], T2_FILIFORM[1], T2_FILIFORM[2], 0.85f))
    }
    val a = out.toFloatArray(); val b = t2Bend(i, 0f, 0f); for (k in 0 until a.size / 7) b(a, k * 7); PointMesh(a)
}

/** The soft palate and uvula, hinged at the back of the hard palate (origin), swung up in the swallow. */
private fun StereoBodyRenderer.t2SoftPalate(): TriMesh = t2Get("softpalate") {
    val g = T2Geo(); val hy = 3.7f; val zB = 6.3f
    // the free edge is a double (gothic) arch: highest at the midline where the uvula hangs,
    // sweeping down laterally into the palatal arches
    fun edgeY(x: Float) = 2.45f - 0.6f * (abs(x) / 2.0f).coerceAtMost(1f).pow(1.3f)
    g.surf(10, 20, T2_SOFT_PALATE) { v, u ->
        val uu = u * 2f - 1f; val half = 1.25f + (2.0f - 1.25f) * v
        val x = uu * half; val z = zB + 2.7f * v
        val yRoot = 2.62f + (hy - 2.62f) * (1f - uu * uu).coerceAtLeast(0f).pow(0.6f)
        val y = yRoot + (edgeY(x) - yRoot) * v
        t2v(x, y - hy, z - zB)
    }
    // the uvula: a tapered, round-tipped tongue of tissue 10 mm long, hanging from the apex
    val root = t2v(0f, 2.45f - hy, 9.0f - zB); val dir = t2v(0f, -cos(0.17f), sin(0.17f))
    g.path((0..6).map { q -> root + dir * (1.2f * q / 6f) }, { t -> 0.22f - 0.06f * t }, T2_SOFT_PALATE, 1f, 9, true)
    TriMesh(g.baked())
}

/** The laryngeal inlet (origin at its front, below the epiglottis): aryepiglottic folds, arytenoids, false cords. */
private fun StereoBodyRenderer.t2Larynx(): TriMesh = t2Get("larynx") {
    val g = T2Geo()
    for (sd in SIGNS) {
        g.path(listOf(t2v(sd * 0.85f, 0.35f, 0f), t2v(sd * 0.8f, 0.2f, 0.6f), t2v(sd * 0.55f, 0.05f, 1.3f), t2v(sd * 0.3f, -0.05f, 1.7f)), { 0.2f }, T2_LARYNX, 1f, 7, true)
        g.ball(t2v(sd * 0.36f, 0f, 1.65f), 0.27f, T2_LARYNX)
        g.tube(t2v(sd * 0.28f, -0.45f, 0.3f), t2v(sd * 0.72f, -0.45f, 1.4f), 0.12f, 0.12f, T2_LARYNX)
    }
    // the dark airway below the cords
    g.disc(t2v(0f, -0.95f, 0.9f), t2v(0f, 1f, 0f), 0.55f, T2_VALLECULA, 1f, 14, null, 0.9f)
    TriMesh(g.baked())
}

private fun StereoBodyRenderer.t2TongueWave(): TriMesh = t2Get("tonguewave") {
    val g = T2Geo(); g.ell(t2v(0f, 0f, 0f), t2v(1.45f, 0f, 0f), t2v(0f, 0.5f, 0f), t2v(0f, 0f, 1.0f), T2_TONGUE, 1f, 8, 14); TriMesh(g.baked())
}

/** The epiglottis: a leaf of elastic cartilage, stalk at the hinge (origin), free tip up (+y). */
private fun StereoBodyRenderer.t2Epiglottis(): TriMesh = t2Get("epiglottis") {
    val g = T2Geo(); val len = 2.0f
    g.surf(10, 12, T2_EPIGLOTTIS) { u, v ->
        val vv = v * 2f - 1f
        var w = 1.1f * sin(T2PI * (0.15f + 0.85f * u) * 0.62f)
        if (u > 0.72f) w *= sqrt(max(0f, 1f - ((u - 0.72f) / 0.29f).pow(2)))
        t2v(vv * w, u * len, 0.14f * vv * vv - 0.26f * u * u * u)
    }
    TriMesh(g.baked())
}

/** Tour II stop 1: the mouth from inside the gape — teeth, gums, palate, tongue, fauces, epiglottis — swallowing every 9 s. */
internal fun StereoBodyRenderer.drawMouth(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    drawSphereAt(n.x, n.y + 2.5f, n.z + 14f, 5f, 5f, 5f, COL_BAY, COL_LAMP, 0.35f, 0f, 0f, 1f, 0f, sphere, 0f, 0.8f)   // daylight behind the face
    t2Open(i, seconds) {
        t2DrawWorld(t2MouthMesh(i))
        // The swallow: the tongue presses up and rolls a wave backward, then the epiglottis folds
        // back over the laryngeal inlet while the bolus passes, and springs up again.
        val cyc = (seconds % 9f) / 9f
        val wave = cyc / 0.22f
        if (wave < 1f) {
            val zb = 2f + 6.5f * t2sm(wave); val env = sin(T2PI * wave)
            t2Model(t2Rigid(i, zb), 0f, t2TongueMid(zb) - 0.42f + 0.4f * env); t2Draw(t2TongueWave())
        }
        val tilt = t2sm((cyc - 0.14f) / 0.1f) * (1f - t2sm((cyc - 0.36f) / 0.14f))
        t2DrawWorld(t2Filiform(i), false, 2f, true)
        // safeguards: the soft palate swings up to seal the nasopharynx, the larynx rises and
        // tucks under the epiglottis, the vocal folds close, the epiglottis folds down over the inlet
        t2Model(t2Rigid(i, 6.3f), 0f, 3.7f); Matrix.rotateM(model, 0, -25f * tilt, 1f, 0f, 0f); t2Draw(t2SoftPalate())
        val ly = -2.2f + 0.3f * tilt; val lz = 10.45f - 0.2f * tilt
        t2Model(t2Rigid(i, lz), 0f, ly); t2Draw(t2Larynx())
        val close = tilt
        for (sd in SIGNS) {
            val a0 = t2W(t2Rigid(i, lz + 0.25f), sd * 0.06f, ly - 0.75f).copyOf()
            val a1 = t2W(t2Rigid(i, lz + 1.45f), sd * (0.5f - 0.44f * close), ly - 0.75f).copyOf()
            drawStrut(a0[0], a0[1], a0[2], a1[0], a1[1], a1[2], 0.14f, T2_VOCAL_CORD, T2_VOCAL_CORD, 0.2f)
        }
        // the rima glottidis between them: open at rest, closed to a line in the swallow
        val fr = t2Rigid(i, lz + 0.85f, FloatArray(13))
        t2Basis(t2W(fr, 0f, ly - 0.8f).copyOf(), fr[9], fr[10], fr[11], fr[3], fr[4], fr[5], 0.4f * (1f - 0.9f * close), 0.6f, 0.02f, sphere, T2_RIMA, T2_RIMA)
        t2Model(t2Rigid(i, 10.15f), 0f, -2.8f + 0.3f * tilt)
        Matrix.rotateM(model, 0, 8f + 92f * tilt, 1f, 0f, 0f)
        t2Draw(t2Epiglottis())
    }
}

// ======================================================================== stop 1: STOMACH
// 8 mm a unit (Mote 12 mm). The swallow carries the craft from the pharynx through the upper
// oesophageal sphincter and ~31 units (25 cm) of oesophagus, held open to ~3 cm by the bolus
// (pale stratified squamous lining in longitudinal folds, a peristaltic ring squeezing just behind
// the craft), through the lower sphincter and the Z-line, where the pale oesophageal lining meets
// the salmon-red gastric mucosa in a zig-zag, into the stomach: a chamber ~10 cm across with rugae
// (longitudinal folds, heavier toward the greater curvature below), gastric pits, the translucent
// mucus blanket, a pool of acid chyme with food particles, and ahead the antrum narrowing to the
// pylorus, a thick muscular ring round a small opening.

/** Arc position (units along the rail from stop [i]) where the rail progress is [p]. */
private fun StereoBodyRenderer.t2ArcAtP(i: Int, p: Float): Float {
    val rail = t2Rail(i); var lo = 0; var hi = rail.cnt - 1
    while (hi - lo > 1) { val m = (lo + hi) / 2; if (rail.f[m * 13 + 12] < p) lo = m else hi = m }
    val p0 = rail.f[lo * 13 + 12]; val p1 = rail.f[hi * 13 + 12]
    val t = if (p1 > p0) ((p - p0) / (p1 - p0)).coerceIn(0f, 1f) else 0f
    return rail.aMin + (lo + t) * rail.step
}

/** The J-shaped stomach, swept along a curved centreline (node-1 rigid frame), shared by its meshes and animation. */
private class T2Stom(val c: Array<T2V>, val t: Array<T2V>, val nrm: Array<T2V>, val r: FloatArray, val zEntry: Float, val pyl: T2V, val pylT: T2V, val sWave0: Int, val sPyl: Int)

/**
 * Centreline control points (x, y, z, radius) in the stomach stop's rigid frame, the rail running
 * near z: the fundus bulging up and to port above the cardia, the body falling away below (the
 * greater curvature), the antrum sweeping back up to the pylorus on the rail ahead.
 */
private val T2_STOM_CP = arrayOf(
    floatArrayOf(-2.6f, 7.4f, -13.4f, 0.1f), floatArrayOf(-2.5f, 5.6f, -13.2f, 2.6f), floatArrayOf(-2.3f, 3.2f, -12.4f, 3.9f),
    floatArrayOf(-1.9f, -0.6f, -9.4f, 5.3f), floatArrayOf(-1.2f, -3.8f, -4.2f, 6.3f), floatArrayOf(-0.3f, -4.6f, 1.0f, 6.5f),
    floatArrayOf(0.7f, -3.4f, 5.4f, 4.6f), floatArrayOf(0.9f, -1.3f, 8.4f, 2.5f))

private fun StereoBodyRenderer.t2Stom(i: Int): T2Stom = t2Get("stomdata$i") {
    val off = FloatArray(2)
    // the last control points follow the rail itself: the pyloric canal and the duodenal bulb
    val cps = ArrayList<FloatArray>(); for (q in T2_STOM_CP) cps.add(q)
    for ((z, r) in listOf(10.6f to 1.0f, 11.6f to 1.3f, 13.5f to 1.45f)) { t2RailOffset(i, z, off); cps.add(floatArrayOf(off[0], off[1], z, r)) }
    val n = cps.size; val samples = 120
    val c = Array(samples + 1) { t2v(0f, 0f, 0f) }; val rr = FloatArray(samples + 1)
    for (k in 0..samples) {
        val f = k.toFloat() / samples * (n - 1); val i1 = min(f.toInt(), n - 2); val tt = f - i1
        val p0 = cps[max(i1 - 1, 0)]; val p1 = cps[i1]; val p2 = cps[i1 + 1]; val p3 = cps[min(i1 + 2, n - 1)]
        val v = FloatArray(4) { q -> 0.5f * ((2f * p1[q]) + (-p0[q] + p2[q]) * tt + (2f * p0[q] - 5f * p1[q] + 4f * p2[q] - p3[q]) * tt * tt + (-p0[q] + 3f * p1[q] - 3f * p2[q] + p3[q]) * tt * tt * tt) }
        c[k] = t2v(v[0], v[1], v[2]); rr[k] = max(0.05f, v[3])
    }
    val t = Array(samples + 1) { k -> (c[min(k + 1, samples)] - c[max(k - 1, 0)]).unit() }
    val nr = arrayOfNulls<T2V>(samples + 1)
    nr[0] = (t2v(-1f, 0f, 0f) - t[0] * (t2v(-1f, 0f, 0f) dot t[0])).unit()
    for (k in 1..samples) { val q = nr[k - 1]!! - t[k] * (nr[k - 1]!! dot t[k]); nr[k] = if (q.len() < 1e-5f) t2perp(t[k]) else q.unit() }
    // where the rail enters the chamber (the cardia)
    fun inside(p: T2V): Boolean { for (k in 0..samples) if ((p - c[k]).len() < rr[k] * 0.98f) return true; return false }
    var ze = -18f; while (ze < -6f) { t2RailOffset(i, ze, off); if (inside(t2v(off[0], off[1], ze))) break; ze += 0.05f }
    val sW = (0..samples).minByOrNull { abs(c[it].z + 4f) + (if (c[it].y > 0f) 99f else 0f) } ?: 40
    val sP = (0..samples).minByOrNull { abs(c[it].z - 10.6f) } ?: samples
    T2Stom(c, t, Array(samples + 1) { nr[it]!! }, rr, ze, c[sP], t[sP], sW, sP)
}

/** A point on the stomach wall at centreline sample [k], angle [ph] from its reference normal, [inset] toward the axis. */
private fun t2StomWall(st: T2Stom, k: Int, ph: Float, inset: Float): T2V {
    val n = st.nrm[k]; val b = st.t[k] cross n
    return st.c[k] + (n * cos(ph) + b * sin(ph)) * (st.r[k] - inset)
}

/** Ruga height at centreline fraction [s] and wall point [p] (heavier toward the greater curvature, i.e. far from the rail). */
private fun t2RugaH(s: Float, distFromRail: Float): Float {
    val along = t2sm((s - 0.08f) / 0.06f) * (1f - t2sm((s - 0.6f) / 0.12f))
    return along * (0.1f + 0.3f * t2sm((distFromRail - 3f) / 5f))
}

private fun StereoBodyRenderer.t2StomachMeshes(i: Int): Array<ColorVboMesh> = t2Get("stomach$i") {
    val st = t2Stom(i); val ns = st.c.size - 1
    val g = T2Geo(); val glass = T2Geo(); val bits = T2Geo(); val raw = T2Geo(); val rnd = java.util.Random(301L)
    val off = FloatArray(2)
    val sides = 176
    // the wall: gastric mucosa with 14 rugae raised out of it, running lengthwise, heaviest along the
    // greater curvature, smoothing out in the antrum; 8 folds converging on the pyloric opening
    val w = sides + 1; val P = FloatArray((ns + 1) * w * 3)
    for (k in 0..ns) {
        val sF = k.toFloat() / ns; t2RailOffset(i, st.c[k].z, off)
        for (j in 0..sides) {
            val ph = 2f * T2PI * j / sides
            val base = t2StomWall(st, k, ph, 0f)
            val dr = sqrt((base.x - off[0]).pow(2) + (base.y - off[1]).pow(2))
            var fold = 0f
            val hR = t2RugaH(sF, dr)
            if (hR > 0.001f) for (f in 0 until 14) {
                val phk = f * 2f * T2PI / 14f + 0.05f * sin(sF * 22f + f * 1.7f)
                var d = abs(ph - phk) % (2f * T2PI); if (d > T2PI) d = 2f * T2PI - d
                val arc = d * st.r[k]; fold += hR * exp(-(arc / 0.22f).pow(2))
            }
            if (k > st.sPyl - 12) {   // pyloric folds
                val pf = t2sm((k - (st.sPyl - 12f)) / 8f) * (1f - t2sm((k - st.sPyl - 2f) / 3f))
                fold += pf * 0.2f * (0.5f + 0.5f * cos(8f * ph)) * min(1f, st.r[k] / 1.2f)
            }
            val p = t2StomWall(st, k, ph, min(fold, st.r[k] * 0.45f)); val o = (k * w + j) * 3
            P[o] = p.x; P[o + 1] = p.y; P[o + 2] = p.z
        }
    }
    // rows up to the antrum are baked here; the antrum (where the peristaltic wave runs) is its own
    // mesh, whose wall the wave shader indents per frame
    val kA = max(2, st.sWave0 - 8)
    raw.grid(kA, sides, P.copyOf((kA + 1) * w * 3), T2_GASTRIC, 1f)
    // open the cardia where the oesophagus comes in
    g.appendWhere(raw) { x, y, z -> if (z > st.zEntry + 3f) true else { t2RailOffset(i, z, off); (x - off[0]).pow(2) + (y - off[1]).pow(2) > 2.0f * 2.0f } }
    // the areae gastricae: shallow grooves outlining 2-6 mm mamillated patches
    val grooves = ArrayList<Float>()
    val nodes = HashMap<Long, T2V>()
    fun node(k: Int, j: Int): T2V = nodes.getOrPut(k * 1000L + j) {
        val kk = (k + (rnd.nextFloat() - 0.5f) * 0.9f).coerceIn(0f, ns.toFloat()); val ph = (j + (rnd.nextFloat() - 0.5f) * 0.9f) * 2f * T2PI / 64f
        t2StomWall(st, kk.toInt().coerceIn(0, ns), ph, 0.03f)
    }
    for (k in (ns * 0.1f).toInt() until (ns * 0.72f).toInt() step 2) for (j in 0 until 64) {
        val a = node(k, j); val b = node(k, (j + 1) % 64); val c2 = node(k + 2, j)
        for ((u, v) in listOf(a to b, a to c2)) {
            if ((u - v).len() > 1.2f) continue
            grooves.addAll(listOf(u.x, u.y, u.z, 0.7f, 0.3f, 0.3f, 0.35f, v.x, v.y, v.z, 0.7f, 0.3f, 0.3f, 0.35f))
        }
    }
    // the mucus blanket, a fraction of a millimetre thick over the folded mucosa (the rugae sit under it)
    fun pin(k: Int, j: Int): T2V { val o = (k * w + j) * 3; val p = t2v(P[o], P[o + 1], P[o + 2]); return p + (st.c[k] - p).unit() * 0.06f }
    for (k in 0 until kA step 2) for (j in 0 until sides step 4) {
        val k1 = min(kA, k + 2); val j1 = min(sides, j + 4)
        val q = listOf(pin(k, j), pin(k1, j), pin(k1, j1), pin(k, j1))
        if (q[0].z < st.zEntry + 3f) { t2RailOffset(i, q[0].z, off); if ((q[0].x - off[0]).pow(2) + (q[0].y - off[1]).pow(2) < 4.4f) continue }
        if (q[0].z < st.zEntry + 4f) continue      // (none near the cardia, where it would be seen edge-on)
        glass.quad(q[0], q[1], q[2], q[3], T2_MUCUS, 0.3f)
    }
    // a pool of acid chyme along the greater curvature, food particles in and on it
    val yPool = -7.2f
    fun inStom(p: T2V): Boolean { for (k in 0..ns) if ((p - st.c[k]).len() < st.r[k] * 0.93f) return true; return false }
    for (xi in -24 until 24) for (zi in -30 until 26) {
        val x0 = xi * 0.4f; val z0 = zi * 0.4f
        val q = listOf(t2v(x0, yPool, z0), t2v(x0 + 0.4f, yPool, z0), t2v(x0 + 0.4f, yPool, z0 + 0.4f), t2v(x0, yPool, z0 + 0.4f))
        if (q.all { inStom(it) }) glass.quad(q[0], q[1], q[2], q[3], T2_CHYME_ACID, 0.55f)
    }
    var placed = 0; var tries = 0
    while (placed < 40 && tries < 3000) {
        tries++
        val c2 = t2v((rnd.nextFloat() * 2f - 1f) * 7f, yPool - rnd.nextFloat() * 1.6f + 0.15f, -8f + rnd.nextFloat() * 14f)
        if (!inStom(c2)) continue
        val s0 = 0.1f + rnd.nextFloat() * 0.25f
        bits.ell(c2, t2v(s0 * (1f + rnd.nextFloat()), 0f, 0f), t2v(0f, s0 * 0.7f, 0f), t2v(0f, 0f, s0), if (placed % 3 == 0) T2_FOOD_B else T2_FOOD_A, 1f, 4, 6)
        placed++
    }
    val rigid = t2Bend(i, 0f, 0f)
    val gA = grooves.toFloatArray(); for (k in 0 until gA.size / 7) rigid(gA, k * 7)
    // the antrum's wall for the wave shader: position, its centreline point, baked colour, sample index
    val wa = ArrayList<Float>()
    fun pv(k: Int, j: Int): T2V { val o = (k * w + j) * 3; return t2v(P[o], P[o + 1], P[o + 2]) }
    fun emit(k: Int, j: Int) {
        val p = pv(k, j)
        val du = pv(min(k + 1, ns), j) - pv(max(k - 1, 0), j); val dv = pv(k, min(j + 1, sides)) - pv(k, max(j - 1, 0))
        val nn = (du cross dv); val l = nn.len()
        val sh = 0.44f + 0.62f * (if (l > 1e-9f) abs(nn.x * 0.3f + nn.y * 0.83f - nn.z * 0.47f) / l else 0.6f)
        val col = if (k > st.sPyl - 4 && k < st.sPyl + 3) T2_PYLORUS else T2_GASTRIC
        val tp = floatArrayOf(p.x, p.y, p.z, 0f, 0f, 0f, 0f); rigid(tp, 0)
        val c = st.c[k]; val tc = floatArrayOf(c.x, c.y, c.z, 0f, 0f, 0f, 0f); rigid(tc, 0)
        wa.addAll(listOf(tp[0], tp[1], tp[2], tc[0], tc[1], tc[2], min(1f, col[0] * sh), min(1f, col[1] * sh), min(1f, col[2] * sh), 1f, k.toFloat()))
    }
    for (k in kA until ns) for (j in 0 until sides) { emit(k, j); emit(k + 1, j); emit(k + 1, j + 1); emit(k, j); emit(k + 1, j + 1); emit(k, j + 1) }
    t2Get("antrum$i") { T2WaveMesh(wa.toFloatArray()) }
    arrayOf(TriMesh(g.baked(rigid)), TriMesh(glass.baked(rigid)), TriMesh(bits.baked(rigid)), LineMesh(gA))
}

/**
 * A wall mesh that a peristaltic wave indents: each vertex carries its centreline point and sample
 * index, and the shader pulls it toward the axis by up to a quarter of the radius round sample uWave.
 */
private class T2WaveMesh(data: FloatArray) {
    val vbo = makeVbo(data); val count = data.size / 11
    companion object {
        var program = 0; var owner: Any? = null
        var hMvp = 0; var hWave = 0; var hFade = 0; var aPos = 0; var aCen = 0; var aCol = 0; var aK = 0
    }
    fun draw(owner0: Any, mvp: FloatArray, wave: Float, fade: Float) {
        if (owner !== owner0) {
            program = compileProgram(
                """
                attribute vec3 aPos; attribute vec3 aCen; attribute vec4 aCol; attribute float aK;
                uniform mat4 uMvp; uniform float uWave;
                varying vec4 vCol;
                void main() {
                    float d = (aK - uWave) / 4.0;
                    float sq = 0.25 * exp(-d * d);
                    vec3 p = aCen + (aPos - aCen) * (1.0 - sq);
                    vCol = vec4(aCol.rgb * (1.0 - 0.6 * sq), aCol.a);
                    gl_Position = uMvp * vec4(p, 1.0);
                }
                """,
                """
                precision mediump float;
                uniform float uFade;
                varying vec4 vCol;
                void main() { gl_FragColor = vec4(vCol.rgb, vCol.a * uFade); }
                """)
            hMvp = GLES20.glGetUniformLocation(program, "uMvp"); hWave = GLES20.glGetUniformLocation(program, "uWave"); hFade = GLES20.glGetUniformLocation(program, "uFade")
            aPos = GLES20.glGetAttribLocation(program, "aPos"); aCen = GLES20.glGetAttribLocation(program, "aCen"); aCol = GLES20.glGetAttribLocation(program, "aCol"); aK = GLES20.glGetAttribLocation(program, "aK")
            owner = owner0
        }
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(hMvp, 1, false, mvp, 0); GLES20.glUniform1f(hWave, wave); GLES20.glUniform1f(hFade, fade)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 44, 0); GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aCen, 3, GLES20.GL_FLOAT, false, 44, 12); GLES20.glEnableVertexAttribArray(aCen)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 44, 24); GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aK, 1, GLES20.GL_FLOAT, false, 44, 40); GLES20.glEnableVertexAttribArray(aK)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aCen); GLES20.glDisableVertexAttribArray(aCol); GLES20.glDisableVertexAttribArray(aK)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}

/** The pyloric sphincter seen from the lumen: mucosa puckered into 8 folds round an opening of radius [open] (outer radius 1). */
private fun StereoBodyRenderer.t2PylorusIris(open: Float): TriMesh = t2Get("pyl$open") {
    val g = T2Geo()
    g.surf(8, 64, T2_PYLORUS) { u, v ->
        val a = v * 2f * T2PI; val r = 1f + (open - 1f) * u
        val fold = 0.07f * (0.5f + 0.5f * cos(8f * a)) * sin(T2PI * u)
        t2v(cos(a) * (r - fold), sin(a) * (r - fold), 0.18f * u * u - fold * 0.5f)
    }
    TriMesh(g.baked())
}

/**
 * A peristaltic constriction of the wall itself (a unit-radius tube section, z -1.5..1.5): the
 * wall narrows to [depth] of its radius at the middle, gathering the lining into [folds] folds.
 */
private fun StereoBodyRenderer.t2Constriction(depth: Float, folds: Int, col: FloatArray): TriMesh = t2Get("constr$depth.$folds") {
    val g = T2Geo()
    // fading out at both ends into the wall it belongs to
    g.surf(16, 56, col, 1f, { k -> val t = k / 16f; floatArrayOf(col[0], col[1], col[2], t2sm(min(t, 1f - t) / 0.3f)) }) { u, v ->
        val z = (u * 2f - 1f) * 1.8f; val a = v * 2f * T2PI
        val squeeze = (1f - depth) * exp(-(z / 0.75f).pow(2))
        val r = (1f - squeeze) * (1f - 0.12f * squeeze * (0.5f + 0.5f * cos(folds * a)))
        t2v(cos(a) * r * 0.99f, sin(a) * r * 0.99f, z)
    }
    TriMesh(g.baked())
}

/** Oesophageal wall radius at [z] in the stomach stop's frame ([zU] the upper sphincter, [zE] the cardia). */
private fun t2OesR(z: Float, zU: Float, zE: Float): Float {
    val zL = zE - 3f
    return when {
        z < zU + 1.2f -> 0.9f + 0.7f * t2sm((z - zU) / 1.2f)                  // opening out of the slit
        z < zL - 1.5f -> 1.6f
        z < zL + 1.5f -> 1.6f - 0.5f * sin(T2PI * (z - zL + 1.5f) / 3f)       // the lower sphincter: a 3-unit narrowing
        else -> 1.6f + 0.4f * t2sm((z - zL - 1.5f) / (zE - zL - 1.2f))
    }
}

/** The oesophagus from the upper sphincter to the cardia (baked along the rail; node-1 frame). */
private fun StereoBodyRenderer.t2OesophagusMesh(i: Int): TriMesh = t2Get("oes$i") {
    val g = T2Geo(); val st = t2Stom(i)
    val zU = t2UesZ1(i); val zE = st.zEntry + 0.15f; val zZ = st.zEntry - 3.4f     // Z-line inside the lower sphincter
    val rows = 220; val sides = 42; val offO = FloatArray(2)
    fun pt(z: Float, th: Float): T2V {
        val lesR = t2OesR(z, zU, zE); val squeezed = 1f - lesR / 1.6f
        val folds = 0.09f + 0.3f * max(0f, squeezed)
        val fold = if (z < zZ) 1f - folds * (0.5f + 0.5f * cos(7f * th)) else 1f - 0.06f * (0.5f + 0.5f * cos(11f * th + z))
        val flat = if (z < zU + 1.2f) 0.45f + 0.55f * t2sm((z - zU) / 1.2f) else 1f     // the slit opens out
        val r = lesR * fold; return t2v(cos(th) * r * (2f - flat), sin(th) * r * flat, z)
    }
    fun zl(th: Float): Float { val u = (th / (2f * T2PI) * 10f) % 1f; return zZ + 0.45f * (abs(u * 2f - 1f) - 0.5f) }
    for (r in 0 until rows) {
        val zA = zU + (zE - zU) * r / rows; val zB = zU + (zE - zU) * (r + 1) / rows
        for (k in 0 until sides) {
            val tA = 2f * T2PI * k / sides; val tB = 2f * T2PI * (k + 1) / sides
            val col = if ((zA + zB) * 0.5f < zl((tA + tB) * 0.5f)) T2_OES_LINING else T2_GASTRIC
            if (zB > st.zEntry - 0.5f) {       // trim what pokes past the cardia into the chamber (seen edge-on)
                val m = pt((zA + zB) * 0.5f, (tA + tB) * 0.5f); t2RailOffset(i, m.z, offO)
                val q = t2v(m.x + offO[0], m.y + offO[1], m.z)
                if ((0 until st.c.size).any { (q - st.c[it]).len() < st.r[it] * 0.97f }) continue
            }
            g.quad(pt(zA, tA), pt(zB, tA), pt(zB, tB), pt(zA, tB), col)
        }
    }
    t2Bake(g, i, 30f, 40f)
}

/** The upper sphincter's position in the stomach stop's rail coordinates (arc from node 1). */
private fun StereoBodyRenderer.t2UesZ1(i: Int): Float = t2Get("uesz$i") {
    // walk the rail back from the stomach to where it passes the mouth frame's rigid z of the slit
    val r0 = t2Rail(i - 1); val f0 = FloatArray(13); r0.at(0f, f0); val fr = FloatArray(13)
    var a = 0f
    while (a < 40f) { r0.at(a, fr); val rz = (fr[0] - f0[0]) * f0[3] + (fr[1] - f0[1]) * f0[4] + (fr[2] - f0[2]) * f0[5]; if (rz >= T2_UES_Z) break; a += 0.05f }
    val pUes = fr[12]
    java.lang.Float.valueOf(t2ArcAtP(i, pUes))
}

/** Tour II stop 2: the oesophagus (drawn through the swallow) and the stomach. */
internal fun StereoBodyRenderer.drawStomach(n: TourNode, i: Int, seconds: Float) {
    val inOes = routeProgress < i - 0.05f
    if (!inOes && !t2Near(i)) return
    t2Open(i, seconds) { own ->
        t2DrawWorld(t2OesophagusMesh(i))
        // the peristaltic wave: the wall itself closing in 7 folds just behind the craft
        val st = t2Stom(i)
        val shipArc = t2ArcAtP(i, routeProgress)
        val zRing = shipArc - 2.2f
        if (zRing > t2UesZ1(i) + 2f && zRing < st.zEntry - 5f) {
            t2Model(t2Frame(i, zRing), 0f, 0f); Matrix.scaleM(model, 0, 1.6f, 1.6f, 1f); t2Draw(t2Constriction(0.4f, 7, T2_OES_LINING), true)
        }
        if (routeProgress > 0.45f) {
            val m = t2StomachMeshes(i)
            t2DrawWorld(m[0])
            lineWidth(1f); t2DrawWorld(m[3], true)
            // antral peristalsis: every 20 s a ring of contraction starts in the body and sweeps to
            // the pylorus, which closes as it arrives and throws the chyme back (retropulsion)
            val f0 = t2Rigid(i, 0f, FloatArray(13))
            val tw = seconds % 20f
            val sPos = st.sWave0 + tw * 0.8f / max(0.05f, (st.c[st.sWave0 + 1] - st.c[st.sWave0]).len())
            val arrive = if (sPos >= st.sPyl - 6) t2sm((sPos - (st.sPyl - 6)) / 6f) else 0f
            // the antral wall itself, indented where the wave has reached
            @Suppress("UNCHECKED_CAST") val am = t2Get("antrum$i") { T2WaveMesh(FloatArray(11)) }
            Matrix.setIdentityM(model, 0); Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            am.draw(sphere, mvp, if (sPos < st.sPyl - 2) sPos else -100f, colorShader.globalFade)
            // the pylorus: open (radius 0.5), closing to a pinhole as the wave arrives
            val open = when { arrive > 0.66f -> 0.1f; arrive > 0.33f -> 0.3f; else -> 0.5f }
            val pp = t2W(f0, st.pyl.x, st.pyl.y, st.pyl.z).copyOf(); val pt = st.pylT
            t2ModelBasis(pp[0], pp[1], pp[2], f0[6] * pt.x + f0[9] * pt.y + f0[3] * pt.z, f0[7] * pt.x + f0[10] * pt.y + f0[4] * pt.z, f0[8] * pt.x + f0[11] * pt.y + f0[5] * pt.z,
                f0[9], f0[10], f0[11], 1.05f, 1.05f, 1f)
            t2Draw(t2PylorusIris(open))
            // food particles bob in the acid, and are thrown back as the pylorus closes
            val back = -1f * arrive * (1f - t2sm((tw - 19f) / 1f))
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, f0[9] * 0.08f * sin(seconds * 0.9f) + f0[3] * (0.15f * sin(seconds * 0.4f) + back), f0[10] * 0.08f * sin(seconds * 0.9f) + f0[4] * (0.15f * sin(seconds * 0.4f) + back), f0[11] * 0.08f * sin(seconds * 0.9f) + f0[5] * (0.15f * sin(seconds * 0.4f) + back))
            t2Draw(m[2])
            t2DrawWorld(m[1], true)
        }
    }
}

// ============================================================================== stop 1: GUT
// 0.8 mm a unit (Mote 1.2 mm). A carpet of ~1300 leaf-shaped villi 0.7-1.1 mm tall and about
// 0.2 mm wide rising from the wall between crypt openings; the nearest few are drawn see-through
// with their lacteal and capillary loop; lumps of chyme drift down the lumen.

private val T2_CORE = arrayOf(      // (angle round the wall from starboard toward up, arc along the rail)
    // three large ones on the starboard wall at eye level, clear of the plicae (seen side-on), three more round the wall
    floatArrayOf(-0.2f, 4.6f), floatArrayOf(0.1f, 5.9f), floatArrayOf(-0.45f, 6.9f),
    floatArrayOf(-1.57f, 5.2f), floatArrayOf(1.2f, 5.6f), floatArrayOf(0.5f, 4.2f)
)

private fun t2CoreSlot(ang: Float, z: Float): Boolean {
    for (c in T2_CORE) {
        var d = abs(ang - c[0]) % (2f * T2PI); if (d > T2PI) d = 2f * T2PI - d
        if (d < 0.26f && abs(z - c[1]) < 0.7f) return true
    }
    return false
}

private fun T2Geo.villus(base: T2V, axis: T2V, maj: T2V, h: Float, ra: Float, rb: Float) {
    // a finger with a blunt, rounded tip (never a point)
    val ts = floatArrayOf(0f, 0.3f, 0.6f, 0.82f, 0.9f, 0.95f, 0.985f, 1f)
    val sc = FloatArray(8) { k -> val t = ts[k]; if (t <= 0.82f) 1.1f - 0.15f * t else 0.97f * sqrt(max(0f, 1f - ((t - 0.82f) / 0.18f).pow(2))) }
    val mn = axis cross maj
    val sides = 8; val w = sides + 1
    val P = FloatArray(8 * w * 3)
    for (i in 0 until 8) {
        val hh = if (i == 7) h * 0.995f else h * ts[i]
        val c = base + axis * hh
        for (j in 0..sides) {
            val a = 2f * T2PI * j / sides; val k = (i * w + j) * 3
            val ox = maj * (cos(a) * ra * sc[i]) + mn * (sin(a) * rb * sc[i])
            P[k] = c.x + ox.x; P[k + 1] = c.y + ox.y; P[k + 2] = c.z + ox.z
        }
    }
    grid(7, sides, P, T2_VILLUS, 1f) { i -> if (i >= 5) T2_VILLUS_TIP else T2_VILLUS }
}

private val T2_GUT_CHUNK = floatArrayOf(-4.4f, 0.4f, 5.0f, 9.7f)

/** The carpet in 6 angular sectors x 3 stretches along the rail, each swayed by its own shear. */
private fun StereoBodyRenderer.t2GutMesh(i: Int, sector: Int, chunk: Int): TriMesh = t2Get("gut$i.$sector.$chunk") {
    val g = T2Geo(); val rnd = java.util.Random(101L)
    val rail = t2Rail(i); val fr = FloatArray(13)
    val nAround = 62; val dzr = 0.46f
    var row = 0; var z = -4.2f
    while (z <= 9.5f) {
        rail.at(z, fr); val p = fr[12]
        for (k in 0 until nAround) {
            val ang = 2f * T2PI * (k + 0.5f * (row % 2) + (rnd.nextFloat() - 0.5f) * 0.4f) / nAround
            val zz = z + (rnd.nextFloat() - 0.5f) * 0.18f
            val h = 0.85f + rnd.nextFloat() * 0.45f
            val tilt = (rnd.nextFloat() - 0.5f) * 0.36f
            val q = rnd.nextFloat() * T2PI
            val lean = rnd.nextFloat() * 0.25f
            if (t2CoreSlot(ang, zz)) continue
            if (t2OnPlica(ang, zz)) continue
            val an = ((ang % (2f * T2PI)) + 2f * T2PI) % (2f * T2PI)
            if ((an / (T2PI / 3f)).toInt().coerceIn(0, 5) != sector) continue
            if (zz < T2_GUT_CHUNK[chunk] || zz >= T2_GUT_CHUNK[chunk + 1]) continue
            val ca = cos(ang); val sa = sin(ang)
            val rw = t2WallR(p, ang) - 0.05f
            val inward = t2v(-ca, -sa, 0f); val tang = t2v(-sa, ca, 0f)
            val axis = (inward + tang * tilt + t2v(0f, 0f, lean)).unit()
            val majRaw = tang * cos(q) + t2v(0f, 0f, 1f) * sin(q)
            val maj = (majRaw - axis * (majRaw dot axis)).unit()
            g.villus(t2v(ca * rw, sa * rw, zz), axis, maj, h, 0.125f, 0.09f)
            // a crypt of Lieberkühn opening between villus bases
            val ang2 = ang + T2PI / nAround; val rw2 = t2WallR(p, ang2) - 0.03f
            g.disc(t2v(cos(ang2) * rw2, sin(ang2) * rw2, zz + dzr * 0.5f), t2v(-cos(ang2), -sin(ang2), 0f), 0.07f, T2_CRYPT, 1f, 6)
        }
        z += dzr; row++
    }
    // the plicae circulares (Kerckring's folds): crescents of the whole mucosa 1.3 units (1 mm)
    // high, each round 200 degrees of the wall, carpeted with villi on both faces
    for ((pk, z0) in T2_PLICA_Z.withIndex()) {
        if (z0 < T2_GUT_CHUNK[chunk] || z0 >= T2_GUT_CHUNK[chunk + 1]) continue
        rail.at(z0, fr); val p = fr[12]
        val thC = pk * 2.094f + 0.5f
        fun sec(th: Float): Int = (((th % (2f * T2PI)) + 2f * T2PI) % (2f * T2PI) / (T2PI / 3f)).toInt().coerceIn(0, 5)
        fun pt(th: Float, t: Float): T2V {
            val dh = abs(th - thC); val h = 2.4f * t2sm((1.75f - dh) / 0.5f)
            val rw = t2WallR(p, th) - 0.05f
            val zz = z0 + 0.5f * cos(T2PI * t) * (1f - 0.5f * sin(T2PI * t)); val r = rw - h * sin(T2PI * t)
            return t2v(cos(th) * r, sin(th) * r, zz)
        }
        // the fold body: a smooth rounded ridge (this sector's share of it)
        val lo = thC - 1.75f; val hi = thC + 1.75f
        // clip to this sector (angles compared modulo 2 pi)
        fun inSec(th: Float) = sec(th) == sector
        val nU = 140
        val ths = (0..nU).map { lo + (hi - lo) * it / nU }
        var u0 = -1
        for (u in 0..nU) {
            val inside = u < nU && inSec((ths[u] + ths[min(u + 1, nU)]) * 0.5f)
            if (inside && u0 < 0) u0 = u
            if ((!inside || u == nU) && u0 >= 0) {
                val uA = u0; val uB = u
                g.surf(uB - uA, 16, T2_VILLUS) { a, bb -> pt(ths[uA] + (ths[uB] - ths[uA]) * a, bb) }
                u0 = -1
            }
        }
        // villi carpeting both faces and the crest at the wall's density, along the local normal
        val rLoc = t2WallR(p, thC) - 1.2f
        var th = thC - 1.7f
        while (th < thC + 1.7f) {
            if (inSec(th)) for (t in floatArrayOf(0.08f, 0.2f, 0.33f, 0.45f, 0.55f, 0.67f, 0.8f, 0.92f)) {
                val a = pt(th, t); val du = pt(th + 0.01f, t) - pt(th - 0.01f, t); val dt = pt(th, t + 0.01f) - pt(th, t - 0.01f)
                var nrm = (du cross dt).unit()
                val inner = t2v(cos(th) * (t2WallR(p, th) - 0.5f), sin(th) * (t2WallR(p, th) - 0.5f), z0)
                if ((nrm dot (a - inner)) < 0f) nrm = nrm * -1f
                val maj = t2perp(nrm)
                g.villus(a, nrm, maj, 0.85f + rnd.nextFloat() * 0.45f, 0.12f, 0.09f)
            }
            th += 0.42f / rLoc
        }
    }
    t2Bake(g, i, 30f, 40f)
}

/**
 * The colon at 12 µm (8 µm a unit), the leg between the gut and the phage stops: a smooth
 * mucosa with no villi, dotted with the openings of the crypts (~30 µm across, 10 units apart),
 * under a translucent inner mucus layer; bacteria (the BodyField) fill the lumen beyond it.
 */
private fun StereoBodyRenderer.t2ColonMeshes(i: Int): Array<ColorVboMesh> = t2Get("colon$i") {
    val g = T2Geo(); val glass = T2Geo(); val rnd = java.util.Random(71L)
    val a0 = t2ArcAtP(i, i + 0.2f); val a1 = t2ArcAtP(i, i + 0.85f); val R = 3.9f
    g.surf(60, 36, T2_COLON) { v, u -> val a = u * 2f * T2PI; t2v(cos(a) * R, sin(a) * R, a0 + (a1 - a0) * v) }
    var z = a0 + 1f; var row = 0
    while (z < a1 - 1f) {
        for (k in 0 until 3) {
            val a = (k + 0.5f * (row % 2)) * 2f * T2PI / 3f + (rnd.nextFloat() - 0.5f) * 0.5f
            val zz = z + (rnd.nextFloat() - 0.5f) * 2f
            g.disc(t2v(cos(a) * (R - 0.02f), sin(a) * (R - 0.02f), zz), t2v(-cos(a), -sin(a), 0f), 1.6f, T2_CRYPT, 1f, 16)
            g.torus(t2v(cos(a) * (R - 0.05f), sin(a) * (R - 0.05f), zz), t2v(cos(a), sin(a), 0f), 1.75f, 0.18f, T2_COLON_RIM, 1f, 18, 5)
        }
        z += 5f; row++
    }
    glass.surf(40, 30, T2_MUCUS, 0.22f) { v, u -> val a = u * 2f * T2PI; t2v(cos(a) * (R - 0.8f), sin(a) * (R - 0.8f), a0 + (a1 - a0) * v) }
    arrayOf(t2Bake(g, i, 30f, 40f), t2Bake(glass, i, 30f, 40f))
}

/** Kerckring's folds: positions along the rail (around the gut stop). */
private val T2_PLICA_Z = floatArrayOf(-2f, 3.5f, 8f)
private fun t2OnPlica(ang: Float, z: Float): Boolean {
    for ((pk, z0) in T2_PLICA_Z.withIndex()) {
        if (abs(z - z0) > 0.6f) continue
        val thC = pk * 2.094f + 0.5f
        var d = abs(ang - thC) % (2f * T2PI); if (d > T2PI) d = 2f * T2PI - d
        if (d < 1.75f) return true
    }
    return false
}

/** A villus for the lit shader: base at z = 0, tip at z = 1, radius 1 (scaled per villus). */
private fun StereoBodyRenderer.t2VillusMesh(): LitMesh = t2Get("villusmesh") {
    t2LitSurf(12, 10) { u, v, out ->
        val r = if (u <= 0.82f) 1.1f - 0.15f * u else 0.97f * sqrt(max(0f, 1f - ((u - 0.82f) / 0.18f).pow(2)))
        val a = v * 2f * T2PI
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = u
    }
}

/** Tour II stop 2: the villous carpet of the small intestine, the nearest villi opened to show the lacteal and capillaries. */
internal fun StereoBodyRenderer.drawGut(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    // past the drop to 12 µm (the colon leg) the small intestine's villi are 80 times too small to
    // be this scene: the colon's wall takes over instead
    if (routeProgress > i + 0.05f) {
        val colon = t2sm((routeProgress - i - 0.05f) / 0.06f)
        val keep = colorShader.globalFade; colorShader.globalFade = keep * colon
        val cm = t2ColonMeshes(i); t2DrawWorld(cm[0]); t2DrawWorld(cm[1], true)
        colorShader.globalFade = keep
        if (routeProgress > i + 0.11f) return
        landmarkFade *= 1f - colon; colorShader.globalFade *= 1f - colon
    }
    // the carpet sways like kelp: each sector is sheared along the rail about the wall, so the
    // bases stay put and the tips (about a unit off the wall) swing to and fro
    for (ch in 0 until 3) {
        val fr = t2Frame(i, (T2_GUT_CHUNK[ch] + T2_GUT_CHUNK[ch + 1]) * 0.5f)
        for (sec in 0 until 6) {
            val th = (sec + 0.5f) * T2PI / 3f
            val nx = fr[6] * cos(th) + fr[9] * sin(th); val ny = fr[7] * cos(th) + fr[10] * sin(th); val nz = fr[8] * cos(th) + fr[11] * sin(th)
            val c = 0.1f * sin(seconds * 0.9f + 1.1f * sec + 0.7f * ch)
            val R = 4.6f + fr[0] * nx + fr[1] * ny + fr[2] * nz
            val D = floatArrayOf(fr[3], fr[4], fr[5]); val N = floatArrayOf(nx, ny, nz)
            for (col in 0..2) for (row in 0..2) model[col * 4 + row] = (if (col == row) 1f else 0f) - c * D[row] * N[col]
            model[3] = 0f; model[7] = 0f; model[11] = 0f; model[15] = 1f
            model[12] = D[0] * c * R; model[13] = D[1] * c * R; model[14] = D[2] * c * R
            t2Draw(t2GutMesh(i, sec, ch))
        }
    }
    val vm = t2VillusMesh()
    t2LinesBegin()
    for ((k, c) in T2_CORE.withIndex()) {
        val ang = c[0]; val z = c[1]
        val fr = t2Frame(i, z)
        val rw = t2WallR(fr[12], ang) - 0.05f
        val ca = cos(ang); val sa = sin(ang)
        val bx = fr[0] + (fr[6] * ca + fr[9] * sa) * rw; val by = fr[1] + (fr[7] * ca + fr[10] * sa) * rw; val bz = fr[2] + (fr[8] * ca + fr[11] * sa) * rw
        // inward, swaying with the flow
        val sw = 0.05f * sin(seconds * 1.1f + k * 1.9f); val sl = 0f
        val ix = -(fr[6] * ca + fr[9] * sa); val iy = -(fr[7] * ca + fr[10] * sa); val iz = -(fr[8] * ca + fr[11] * sa)
        val tx = -fr[6] * sa + fr[9] * ca; val ty = -fr[7] * sa + fr[10] * ca; val tz = -fr[8] * sa + fr[11] * ca
        var ax = ix + tx * sw + fr[3] * sl; var ay = iy + ty * sw + fr[4] * sl; var az = iz + tz * sw + fr[5] * sl
        val al = sqrt(ax * ax + ay * ay + az * az); ax /= al; ay /= al; az /= al
        // the villus pump: every 8 s the villus shortens by an eighth (squeezing the lacteal)
        val pt = (seconds + k * 1.3f) % 8f
        val pump = 1f - 0.12f * (if (pt < 1f) t2sm(pt) else 1f - t2sm((pt - 1f) / 2f))
        val h = 1.9f * pump
        // centre: the blind-ended lacteal; an arteriole up one side of the core, a venule down the
        // other, joined by capillary rungs and a loop just under the tip
        drawStrut(bx + ax * 0.05f, by + ay * 0.05f, bz + az * 0.05f, bx + ax * (h - 0.15f), by + ay * (h - 0.15f), bz + az * (h - 0.15f), 0.05f, T2_LACTEAL, T2_LACTEAL, 0.6f)
        val bxv = ay * tz - az * ty; val byv = az * tx - ax * tz; val bzv = ax * ty - ay * tx
        val off = 0.14f
        fun pt3(side: Float, t: Float, lat: Float = 0f, o: FloatArray) {
            o[0] = bx + ax * (h * t) + tx * (off * side) + bxv * lat; o[1] = by + ay * (h * t) + ty * (off * side) + byv * lat; o[2] = bz + az * (h * t) + tz * (off * side) + bzv * lat
        }
        val pa = FloatArray(3); val pb = FloatArray(3)
        pt3(1f, 0.02f, 0f, pa); pt3(1f, 0.88f, 0f, pb); t2Seg(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], T2_ARTERIOLE, 1f)
        pt3(-1f, 0.02f, 0f, pa); pt3(-1f, 0.88f, 0f, pb); t2Seg(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], T2_VENULE, 1f)
        for (q in 0 until 4) {   // verticals joining the loops into a net
            val ang = q * T2PI / 2f + 0.8f; var qx0 = 0f; var qy0 = 0f; var qz0 = 0f
            for (r in 0..5) {
                val hh = h * (0.16f + r * 0.14f); val cx = cos(ang) * 0.14f; val cy = sin(ang) * 0.14f
                val xx = bx + ax * hh + tx * cx + bxv * cy; val yy = by + ay * hh + ty * cx + byv * cy; val zz = bz + az * hh + tz * cx + bzv * cy
                if (r > 0) t2Seg(qx0, qy0, qz0, xx, yy, zz, T2_CAPILLARY, 0.85f)
                qx0 = xx; qy0 = yy; qz0 = zz
            }
        }
        for (r in 1..5) {   // the subepithelial capillary net: loops round the villus just under its surface
            val t = r / 6.4f
            for (hs in SIGNS) {
                var qx0 = 0f; var qy0 = 0f; var qz0 = 0f
                for (q in 0..8) {
                    val ang = T2PI * q / 8f; val rr = 0.14f
                    val cx = cos(ang) * rr; val cy = hs * sin(ang) * rr
                    val hh = h * (t + 0.03f * q / 8f + 0.02f * sin(ang * 3f + r))
                    val xx = bx + ax * hh + tx * cx + bxv * cy; val yy = by + ay * hh + ty * cx + byv * cy; val zz = bz + az * hh + tz * cx + bzv * cy
                    if (q > 0) t2Seg(qx0, qy0, qz0, xx, yy, zz, T2_CAPILLARY, 0.85f)
                    qx0 = xx; qy0 = yy; qz0 = zz
                }
            }
        }
        var qx = 0f; var qy = 0f; var qz = 0f
        for (q in 0..8) {   // the loop over the top, 0.1 below the tip
            val a = T2PI * q / 8f
            val xx = bx + ax * (h * 0.88f + 0.1f * sin(a)) + tx * (off * cos(a)); val yy = by + ay * (h * 0.88f + 0.1f * sin(a)) + ty * (off * cos(a)); val zz = bz + az * (h * 0.88f + 0.1f * sin(a)) + tz * (off * cos(a))
            if (q > 0) t2Seg(qx, qy, qz, xx, yy, zz, T2_CAPILLARY, 1f)
            qx = xx; qy = yy; qz = zz
        }
        t2Q[0] = bx; t2Q[1] = by; t2Q[2] = bz
        run {   // the villus's outline as seen from the camera, so the net reads as inside it
            var sx = ay * (camNowZ - bz) - az * (camNowY - by); var sy = az * (camNowX - bx) - ax * (camNowZ - bz); var sz = ax * (camNowY - by) - ay * (camNowX - bx)
            val sl = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-4f); sx /= sl; sy /= sl; sz /= sl
            var px0 = 0f; var py0 = 0f; var pz0 = 0f
            for (q in 0..16) {
                val t = q / 16f; val ang = T2PI * t
                val hh: Float; val rr: Float
                if (t < 0.4f) { hh = h * 0.86f * (t / 0.4f); rr = 0.21f } else if (t > 0.6f) { hh = h * 0.86f * ((1f - t) / 0.4f); rr = -0.21f }
                else { val a2 = (t - 0.4f) / 0.2f * T2PI; hh = h * 0.86f + 0.12f * sin(a2); rr = 0.21f * cos(a2) }
                val xx = bx + ax * hh + sx * rr; val yy = by + ay * hh + sy * rr; val zz = bz + az * hh + sz * rr
                if (q > 0) t2Seg(px0, py0, pz0, xx, yy, zz, T2_VILLUS_EDGE, 0.95f)
                px0 = xx; py0 = yy; pz0 = zz
            }
        }
        t2Basis(t2Q, ax, ay, az, tx, ty, tz, 0.22f, 0.16f, h, vm, T2_VILLUS, T2_VILLUS_TIP, 0.45f, 0.05f)
    }
    t2LinesEnd(1.5f)
    // chyme: irregular lumps of partly digested food carried down the lumen
    for (k in 0 until (if (quality == 0) 10 else 5)) {
        val z = ((seconds * 0.45f + k * 1.37f) % 14f) - 4f
        val a = k * 2.1f + 0.4f; val r = 1.55f + 0.25f * sin(k * 1.7f)
        val fr = t2Frame(i, z)
        val s = 0.1f + 0.06f * ((k * 7) % 3)
        t2Blob(fr, cos(a) * r, sin(a) * r, s * 1.3f, s * 0.8f, s, T2_CHYME, T2_CHYME, 1f, 0.05f)
    }
}

// ============================================================================ stop 2: PHAGE
// 80 nm a unit (Mote 120 nm). Overhead, the underside of one E. coli: a rod 2 µm x 1 µm (25 x 12.5
// units) with a translucent outer membrane and peritrichous flagella trailing in lazy left-handed
// helices. T4 phages (prolate icosahedral head 111 x 86 nm, contractile tail 100 nm, hexagonal
// baseplate, six kinked long tail fibres) sit on it: one lands, contracts its sheath and injects;
// the rest are at other stages. Off to port another infected cell fills with progeny and bursts.

/** Host A's axis height above the rail at the phage stop. */
private const val T2_HOST_Y = 7.8f

private fun StereoBodyRenderer.t2PhageHead(): LitMesh = t2Get("phagehead") {
    val g = T2Geo(); val e = 0.16f; val sc = 0.6f
    val top = t2v(0f, 0f, (1f + e) * sc); val bot = t2v(0f, 0f, -(1f + e) * sc)
    val up = Array(5) { k -> val a = 2f * T2PI * k / 5f; t2v(0.894f * cos(a) * sc, 0.894f * sin(a) * sc, (0.447f + e) * sc) }
    val lo = Array(5) { k -> val a = 2f * T2PI * (k + 0.5f) / 5f; t2v(0.894f * cos(a) * sc, 0.894f * sin(a) * sc, -(0.447f + e) * sc) }
    for (k in 0 until 5) {
        val k1 = (k + 1) % 5
        g.tri(top, up[k], up[k1], T2_PHAGE_HEAD); g.tri(up[k], lo[k], up[k1], T2_PHAGE_HEAD)
        g.tri(up[k1], lo[k], lo[k1], T2_PHAGE_HEAD); g.tri(bot, lo[k1], lo[k], T2_PHAGE_HEAD)
    }
    T2Lit(g.lit())
}

private fun StereoBodyRenderer.t2Sheath(): LitMesh = t2Get("sheath") {
    t2LitSurf(48, 12) { u, v, out ->
        val r = 1f - 0.14f * (0.5f + 0.5f * cos(u * 2f * T2PI * 12f)); val a = v * 2f * T2PI
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = u * 2f - 1f
    }
}

/** A closed hexagonal prism, circumradius 1, z from -1 to 1 (baseplate, collar). */
private fun StereoBodyRenderer.t2Hex(): LitMesh = t2Get("hex") {
    t2LitSurf(12, 24) { u, v, out ->
        val a = v * 2f * T2PI; val seg = (a % (T2PI / 3f)) - T2PI / 6f; val rh = cos(T2PI / 6f) / cos(seg)
        val r: Float; val z: Float
        if (u < 0.25f) { r = rh * u / 0.25f; z = -1f } else if (u < 0.75f) { r = rh; z = (u - 0.25f) / 0.5f * 2f - 1f } else { r = rh * (1f - u) / 0.25f; z = 1f }
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = z
    }
}

private fun StereoBodyRenderer.t2Capsule(): LitMesh = t2Get("capsule50") {
    t2LitSurf(24, 20) { u, v, out ->
        val r = 0.5f; val half = 0.5f; val t = u * 2f - 1f; val cap = r / (half + r)
        val z: Float; val rr: Float
        if (t < -1f + cap) { val k = (t + 1f) / cap; val an = (1f - k) * T2PI / 2f; z = -half - sin(an) * r; rr = cos(an) * r }
        else if (t > 1f - cap) { val k = (1f - t) / cap; val an = (1f - k) * T2PI / 2f; z = half + sin(an) * r; rr = cos(an) * r }
        else { z = t / (1f - cap) * half; rr = r }
        val a = v * 2f * T2PI; out[0] = cos(a) * rr; out[1] = sin(a) * rr; out[2] = z
    }
}

/** A left-handed flagellar filament: helix radius 2.2 (0.35 µm), 1.5 turns over 40 units (3.2 µm), 20 nm thick. */
private fun StereoBodyRenderer.t2Flagellum(): LitMesh = t2Get("flagellum") {
    t2LitSurf(150, 6) { u, v, out ->
        val rh = 2.2f * min(1f, u / 0.06f); val a = -u * 1.5f * 2f * T2PI; val b = v * 2f * T2PI
        out[0] = rh * cos(a) + 0.13f * cos(b); out[1] = rh * sin(a) + 0.13f * sin(b); out[2] = 40f * u
    }
}

/** A small curved plate of cell wall (for the burst). */
private fun StereoBodyRenderer.t2WallPlate(): LitMesh = t2Get("wallplate") {
    t2LitSurf(5, 7) { u, v, out ->
        val th = (u - 0.5f) * 0.7f; val ph = (v - 0.5f) * 0.9f
        out[0] = sin(ph) * cos(th); out[1] = sin(th); out[2] = cos(ph) * cos(th)
    }
}

/** A complete phage (head, collar, sheath, baseplate) along +z from the baseplate at z = 0. */
private fun T2Geo.miniPhage(c: T2V, ax: T2V, col: FloatArray) {
    val a = ax.unit(); val e1 = t2perp(a); val e2 = a cross e1
    tube(c, c + a * 1.25f, 0.13f, 0.13f, col, 1f, 6, false)
    ell(c + a * 0.04f, e1 * 0.33f, a * 0.07f, e2 * 0.33f, col, 1f, 4, 6)
    val hc = c + a * 2.05f
    val sc = 0.6f; val e = 0.16f
    val top = hc + a * ((1f + e) * sc); val bot = hc - a * ((1f + e) * sc)
    val up = Array(5) { k -> val q = 2f * T2PI * k / 5f; hc + (e1 * cos(q) + e2 * sin(q)) * (0.894f * sc) + a * ((0.447f + e) * sc) }
    val lo = Array(5) { k -> val q = 2f * T2PI * (k + 0.5f) / 5f; hc + (e1 * cos(q) + e2 * sin(q)) * (0.894f * sc) - a * ((0.447f + e) * sc) }
    for (k in 0 until 5) { val k1 = (k + 1) % 5; tri(top, up[k], up[k1], col); tri(up[k], lo[k], up[k1], col); tri(up[k1], lo[k], lo[k1], col); tri(bot, lo[k1], lo[k], col) }
    // six long tail fibres, retracted: folded up along the sheath from the baseplate corners
    for (k in 0 until 6) {
        val q = T2PI / 6f + k * T2PI / 3f; val d = e1 * cos(q) + e2 * sin(q)
        tube(c + d * 0.3f, c + d * 0.2f + a * 1.25f, 0.035f, 0.035f, col, 1f, 3, false)
    }
}

private fun StereoBodyRenderer.t2MiniPhage(): LitMesh = t2Get("miniphage") {
    val g = T2Geo(); g.miniPhage(t2v(0f, 0f, -1f), t2v(0f, 0f, 1f), T2_PHAGE_HEAD); T2Lit(g.lit())
}

/** Progeny packed inside a host (host-local: axis +z, radius 6). */
private fun StereoBodyRenderer.t2Progeny(): LitMesh = t2Get("progeny") {
    val g = T2Geo(); val rnd = java.util.Random(23L)
    repeat(100) {   // a burst of 100-200 for T4
        val r = 4.5f * sqrt(rnd.nextFloat()); val a = rnd.nextFloat() * 2f * T2PI; val z = (rnd.nextFloat() * 2f - 1f) * 9f
        val d = t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
        g.miniPhage(t2v(cos(a) * r, sin(a) * r, z) - d * 1f, d, T2_PHAGE_HEAD)
    }
    T2Lit(g.lit())
}

/** Progeny streaming out: 40 whole phages along rays in a 35-degree cone about +z, 0..12 units out. */
private fun StereoBodyRenderer.t2Stream(): LitMesh = t2Get("stream") {
    val g = T2Geo(); val rnd = java.util.Random(29L)
    repeat(80) {
        val th = 0.61f * sqrt(rnd.nextFloat()); val ph = rnd.nextFloat() * 2f * T2PI; val d = 12f * (it + rnd.nextFloat()) / 80f
        val dir = t2v(sin(th) * cos(ph), sin(th) * sin(ph), cos(th))
        val ax = t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
        g.miniPhage(dir * d - ax * 1f, ax, T2_PHAGE_HEAD)
    }
    T2Lit(g.lit())
}

/** The lysed host's ghost: a capsule (radius 1 about +z, half-length 25/12 of it) with a ragged hole at the -z pole. */
private fun StereoBodyRenderer.t2Ghost(): LitMesh = t2Get("ghost") {
    val g = T2Geo(); val half = 6.5f / 6f; val hole = 0.36f
    // body and +z cap
    g.surf(20, 24, T2_ECOLI) { u, v ->
        val a = v * 2f * T2PI
        if (u < 0.6f) { val z = -half + 2f * half * (u / 0.6f); t2v(cos(a), sin(a), z) }
        else { val th = (u - 0.6f) / 0.4f * T2PI / 2f; t2v(cos(a) * cos(th), sin(a) * cos(th), half + sin(th)) }
    }
    // -z cap, open round the pole
    g.surf(8, 24, T2_ECOLI) { u, v ->
        val a = v * 2f * T2PI; val th = u * (T2PI / 2f - hole)
        t2v(cos(a) * cos(th), sin(a) * cos(th), -half - sin(th))
    }
    // torn flaps round the hole, bent outward
    for (k in 0 until 12) {
        val a0 = k * T2PI / 6f; val a1 = a0 + T2PI / 6f; val am = (a0 + a1) * 0.5f
        val rim = cos(T2PI / 2f - hole); val zr = -half - sin(T2PI / 2f - hole)
        val p0 = t2v(cos(a0) * rim, sin(a0) * rim, zr); val p1 = t2v(cos(a1) * rim, sin(a1) * rim, zr)
        val tip = t2v(cos(am) * (rim + 0.05f), sin(am) * (rim + 0.05f), zr - 0.1f)
        g.tri(p0, p1, tip, T2_ECOLI)
    }
    T2Lit(g.lit())
}

/** Type 1 fimbriae on host A: fine straight hairs about 1 µm long (host-local, rigid frame), clear of the lane. */
private fun StereoBodyRenderer.t2Fimbriae(i: Int): LineMesh = t2Get("fimbriae$i") {
    val rnd = java.util.Random(37L); val out = ArrayList<Float>(); val cy = T2_HOST_Y; val cz = 3f
    var n = 0; var tries = 0
    while (n < 80 && tries < 4000) {
        tries++
        val a = rnd.nextFloat() * 2f * T2PI; val z = (rnd.nextFloat() * 2f - 1f) * 12f
        val zc = z.coerceIn(-6.25f, 6.25f); val capZ = z - zc
        val rr = sqrt(max(0f, 1f - (capZ / 6.25f).pow(2)))
        val nrm = t2v(cos(a) * rr, sin(a) * rr, capZ / 6.25f).unit()
        val base = t2v(cos(a) * 6.25f * rr, cy + sin(a) * 6.25f * rr, cz + zc + capZ)
        val jit = t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * 0.5f
        val dir = (nrm + jit).unit(); val len = 10f + 4f * rnd.nextFloat()
        val tip = base + dir * len
        // keep the lane (the rail axis, x = y = 0) clear by 2.5 units
        var ok = true
        for (q in 0..10) { val pp = base + dir * (len * q / 10f); if (pp.x * pp.x + pp.y * pp.y < 6.25f) ok = false }
        if (!ok) continue
        out.addAll(listOf(base.x, base.y, base.z, 0.82f, 0.96f, 0.72f, 0.45f, tip.x, tip.y, tip.z, 0.82f, 0.96f, 0.72f, 0.45f)); n++
    }
    val arr = out.toFloatArray(); val b = t2Bend(i, 0f, 0f); for (k in 0 until arr.size / 7) b(arr, k * 7); LineMesh(arr)
}

/**
 * One T4 at surface point P (outward normal n, reference e ⟂ n): baseplate [lift] off the surface,
 * sheath contracted by [contract] (1.25 -> 0.53 units, the tail tube then 0.72 into the envelope),
 * tail fibres from folded (0) to planted (1), head emptied by [empty], DNA [dna] of the way in.
 */
private fun StereoBodyRenderer.t2PhageAt(P: FloatArray, n: FloatArray, e: FloatArray, lift: Float, contract: Float, spread: Float, empty: Float, dna: Float, alpha: Float) {
    if (alpha < 0.02f) return
    val px = P[0]; val py = P[1]; val pz = P[2]; val nx = n[0]; val ny = n[1]; val nz = n[2]; val ex = e[0]; val ey = e[1]; val ez = e[2]
    val fx = ny * ez - nz * ey; val fy = nz * ex - nx * ez; val fz = nx * ey - ny * ex
    val q = FloatArray(3)
    fun at(d: Float): FloatArray { q[0] = px + nx * d; q[1] = py + ny * d; q[2] = pz + nz * d; return q }
    val ls = 1.25f - 0.72f * contract; val rs = 0.13f + 0.07f * contract
    t2Basis(at(lift + 0.07f), nx, ny, nz, ex, ey, ez, 0.33f, 0.33f, 0.06f, t2Hex(), T2_PHAGE_PLATE, T2_PHAGE_PLATE, alpha, 0.15f)
    t2Basis(at(lift + 0.14f + ls * 0.5f), nx, ny, nz, ex, ey, ez, rs, rs, ls * 0.5f, t2Sheath(), T2_PHAGE_TAIL, T2_PHAGE_TAIL, alpha, 0.15f)
    t2Basis(at(lift + 0.19f + ls), nx, ny, nz, ex, ey, ez, 0.2f, 0.2f, 0.05f, t2Hex(), T2_PHAGE_PLATE, T2_PHAGE_PLATE, alpha, 0.15f)
    t2Basis(at(lift + 0.24f + ls + 0.695f), nx, ny, nz, ex, ey, ez, 1f, 1f, 1f, t2PhageHead(), T2_PHAGE_HEAD, T2_PHAGE_HEAD, alpha * (1f - 0.6f * empty), 0.15f)
    if (contract > 0.01f) {   // the inner tail tube punched through the outer membrane
        val lt = 0.72f * contract
        t2Basis(at(lift - lt * 0.5f + 0.02f), nx, ny, nz, ex, ey, ez, 0.05f, 0.05f, lt * 0.5f, cylinder, T2_GTP_CAP, COL_LAMP, alpha, 0.3f)
    }
    // Six long tail fibres from the baseplate corners, kinked at the knee, their tips planted on the host.
    val bx = px + nx * (lift + 0.07f); val by = py + ny * (lift + 0.07f); val bz = pz + nz * (lift + 0.07f)
    for (k in 0 until 6) {
        val a = T2PI / 6f + k * T2PI / 3f
        val dx = ex * cos(a) + fx * sin(a); val dy = ey * cos(a) + fy * sin(a); val dz = ez * cos(a) + fz * sin(a)
        val rx = bx + dx * 0.33f; val ry = by + dy * 0.33f; val rz = bz + dz * 0.33f
        val kr = 0.5f + 0.35f * spread; val ku = 0.55f - 0.1f * spread
        val kx = rx + dx * kr + nx * ku; val ky = ry + dy * kr + ny * ku; val kz = rz + dz * kr + nz * ku
        val fr0 = 1.45f; val footX = px + dx * fr0; val footY = py + dy * fr0; val footZ = pz + dz * fr0
        val hx = kx + dx * 0.5f - nx * 0.55f; val hy = ky + dy * 0.5f - ny * 0.55f; val hz = kz + dz * 0.5f - nz * 0.55f
        val tx = hx + (footX - hx) * spread; val ty = hy + (footY - hy) * spread; val tz = hz + (footZ - hz) * spread
        t2Seg(rx, ry, rz, kx, ky, kz, T2_PHAGE_TAIL, 0.95f * alpha); t2Seg(kx, ky, kz, tx, ty, tz, T2_PHAGE_TAIL, 0.95f * alpha)
    }
    if (dna > 0.01f) {   // the genome running down the tube, through both membranes, coiling into the cell
        val h = at(lift + 0.24f + ls + 0.695f); val hx0 = h[0]; val hy0 = h[1]; val hz0 = h[2]
        val d = at(-0.75f); val d0 = d[0]; val d1 = d[1]; val d2 = d[2]
        t2Seg(hx0, hy0, hz0, d0, d1, d2, T2_DNA, 1f)
        var px = d0; var py = d1; var pz = d2
        val steps = (48 * dna).toInt()
        for (q in 1..steps) {
            val t = q / 48f; val an = t * 6f * 2f * T2PI; val depth = 0.75f + 3f * t; val r = 0.5f * min(1f, t * 8f)
            val cx = cos(an) * r; val cy = sin(an) * r
            val xx = P[0] - nx * depth + ex * cx + fx * cy; val yy = P[1] - ny * depth + ey * cx + fy * cy; val zz = P[2] - nz * depth + ez * cx + fz * cy
            t2Seg(px, py, pz, xx, yy, zz, T2_DNA, 1f)
            px = xx; py = yy; pz = zz
        }
    }
}

/** Tour II stop 3: T4 phages on the E. coli overhead — landing, contracting, injecting — and a second host bursting. */
internal fun StereoBodyRenderer.drawPhage(n: TourNode, i: Int, seconds: Float) {
    if (routeProgress < i - 0.38f) return      // (the colon leg before it is at 12 µm, not 120 nm)
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        val dx = f0[3]; val dy = f0[4]; val dz = f0[5]; val ux = f0[9]; val uy = f0[10]; val uz = f0[11]
        val cap = t2Capsule()
        // ---- host A overhead: cytoplasm and a translucent outer membrane 20 nm out
        val hc = t2W(f0, 0f, T2_HOST_Y, 3f).copyOf()
        // cytoplasm bounded by the inner membrane (r 5.65); the periplasm and peptidoglycan to 6.0;
        // the outer membrane at 6.25
        t2Basis(hc, dx, dy, dz, ux, uy, uz, 11.3f, 11.3f, 11.8f, cap, T2_ECOLI, T2_ECOLI_OM, 1f, 0.05f)
        if (own) t2DrawWorld(t2Fimbriae(i), true)
        if (own) {   // flagella: long left-handed helices trailing from the body, turning (slowed ~200x)
            val fl = t2Flagellum()
            for ((k, s) in arrayOf(floatArrayOf(2.6f, 9f), floatArrayOf(3.5f, 12f), floatArrayOf(1.9f, 5f), floatArrayOf(4.3f, 7f), floatArrayOf(3.14f, 14.3f)).withIndex()) {
                val phi = s[0]; val zo = s[1]
                val lx = sin(phi) * 5.9f; val ly = T2_HOST_Y - cos(phi) * 5.9f
                val o = t2W(f0, lx, ly, zo).copyOf()
                val ax = sin(phi) * 0.3f; val ay = -cos(phi) * 0.3f
                var zx = f0[6] * ax + f0[9] * ay + dx; var zy = f0[7] * ax + f0[10] * ay + dy; var zz = f0[8] * ax + f0[11] * ay + dz
                val zl = sqrt(zx * zx + zy * zy + zz * zz); zx /= zl; zy /= zl; zz /= zl
                // e1 ⟂ axis, e2 = axis x e1; the helix turns counter-clockwise seen from its tip (a run)
                var e1x = uy * zz - uz * zy; var e1y = uz * zx - ux * zz; var e1z = ux * zy - uy * zx
                val el = sqrt(e1x * e1x + e1y * e1y + e1z * e1z).coerceAtLeast(1e-4f); e1x /= el; e1y /= el; e1z /= el
                val e2x = zy * e1z - zz * e1y; val e2y = zz * e1x - zx * e1z; val e2z = zx * e1y - zy * e1x
                val th = seconds * 3.1f + k * 1.3f; val c = cos(th); val sn = sin(th)
                t2Basis(o, zx, zy, zz, e1x * c + e2x * sn, e1y * c + e2y * sn, e1z * c + e2z * sn, 1f, 1f, 1f, fl, T2_FLAGELLUM, T2_FLAGELLUM, 1f, 0.3f)
                val bb = t2W(f0, sin(phi) * 6.3f, T2_HOST_Y - cos(phi) * 6.3f, zo).copyOf()   // the basal body / hook where it leaves the cell
                t2Basis(bb, f0[6] * sin(phi) - f0[9] * cos(phi), f0[7] * sin(phi) - f0[10] * cos(phi), f0[8] * sin(phi) - f0[11] * cos(phi), dx, dy, dz, 0.25f, 0.25f, 0.08f, t2Hex(), T2_FLAGELLUM, T2_FLAGELLUM, 1f, 0.2f)
            }
        }
        t2Basis(hc, dx, dy, dz, ux, uy, uz, 12f, 12f, 12.35f, cap, T2_PERIPLASM, T2_PERIPLASM, 0.25f, 0.1f)
        t2Basis(hc, dx, dy, dz, ux, uy, uz, 12.5f, 12.5f, 12.5f, cap, T2_ECOLI_OM, T2_ECOLI_OM, 0.28f, 0.1f)
        // ---- phages on host A: angle phi from the underside (+ toward starboard), along z
        t2LinesBegin()
        val P = FloatArray(3); val N = FloatArray(3); val E = FloatArray(3)
        fun site(phi: Float, z: Float, rad: Float = 6.25f) {
            val w = t2W(f0, sin(phi) * rad, T2_HOST_Y - cos(phi) * rad, z); P[0] = w[0]; P[1] = w[1]; P[2] = w[2]
            val lx = sin(phi); val ly = -cos(phi)
            N[0] = f0[6] * lx + f0[9] * ly; N[1] = f0[7] * lx + f0[10] * ly; N[2] = f0[8] * lx + f0[11] * ly
            E[0] = dx; E[1] = dy; E[2] = dz
        }
        site(0.62f, 4.8f); t2PhageAt(P, N, E, 0f, 1f, 1f, 1f, 0f, 1f)            // spent: contracted, head empty
        site(0.95f, -0.8f); t2PhageAt(P, N, E, 0f, 0f, 1f, 0f, 0f, 1f)           // fibres planted, sheath still extended
        site(-1.05f, 7.2f); t2PhageAt(P, N, E, 0f, 1f, 1f, 1f, 0f, 1f)
        site(0.78f, 10.5f); t2PhageAt(P, N, E, 0f, 0f, 1f, 0f, 0f, 1f)
        // the featured one: lands, plants its fibres, contracts, injects (a 20 s cycle)
        run {
            val t = (seconds / 20f) % 1f
            val lift = 3f * (1f - t2sm(t / 0.25f)); val spread = t2sm((t - 0.1f) / 0.15f)
            val con = t2sm((t - 0.36f) / 0.08f); val dna = t2sm((t - 0.46f) / 0.3f); val emp = t2sm((t - 0.5f) / 0.3f)
            val a = t2sm(t / 0.04f) * (1f - t2sm((t - 0.94f) / 0.06f))
            site(-0.35f, 6.0f); t2PhageAt(P, N, E, lift, con, spread, emp, dna, a)
        }
        // one still arriving, tail first, from the port side
        run {
            val t = (seconds / 28f) % 1f
            val lift = 6f * (1f - t2sm(t / 0.6f)); val spread = t2sm((t - 0.4f) / 0.2f)
            val a = t2sm(t / 0.05f) * (1f - t2sm((t - 0.94f) / 0.06f))
            site(-1.3f, 9.2f); t2PhageAt(P, N, E, lift, 0f, spread, 0f, 0f, a)
        }
        t2LinesEnd(2f)
        if (!own) return@t2Open
        // ---- host B to port: filling with progeny, bulging, then lysis (lysis clock / the "lysis"
        //      cue): holin and endolysin open the envelope at one point facing us, the progeny spill
        //      out through the tear, and the cell collapses to an empty ghost
        val ph = lysisClock / LYSIS_PERIOD
        val bl = FloatArray(3); run { val w = t2W(f0, -18f, -6f, 30f); bl[0] = w[0]; bl[1] = w[1]; bl[2] = w[2] }
        // axis pointing away from the craft, so the tear (at the -axis pole) faces us
        var ax = -18f * f0[6] - 6f * f0[9] + 30f * f0[3]; var ay = -18f * f0[7] - 6f * f0[10] + 30f * f0[4]; var az = -18f * f0[8] - 6f * f0[11] + 30f * f0[5]
        val al = sqrt(ax * ax + ay * ay + az * az); ax /= al; ay /= al; az /= al
        if (ph < 0.62f) {
            val vis = t2sm((ph - 0.1f) / 0.3f); val swell = 1f + 0.1f * t2sm((ph - 0.35f) / 0.27f)
            t2Basis(bl, ax, ay, az, ux, uy, uz, 1f, 1f, 1f, t2Progeny(), T2_PHAGE_HEAD, T2_PHAGE_HEAD, 1f, 0.3f * vis)
            t2Basis(bl, ax, ay, az, ux, uy, uz, 12f * swell, 12f * swell, 12f, cap, T2_ECOLI, T2_ECOLI_OM, 1f - 0.62f * vis, 0.05f)
            t2Basis(bl, ax, ay, az, ux, uy, uz, 12.5f * swell, 12.5f * swell, 12.5f, cap, T2_ECOLI_OM, T2_ECOLI_OM, 0.28f, 0.1f + 0.5f * t2sm((ph - 0.5f) / 0.12f))
        } else {
            val tb = (ph - 0.62f) / 0.38f; val secs = tb * LYSIS_PERIOD * 0.38f
            val shrink = t2sm(tb / 0.85f)
            val rg = 6f - 1f * shrink
            // the stream: whole progeny phages leaving through the tear at 1.5 units/s
            val pole = FloatArray(3); pole[0] = bl[0] - ax * rg * (6.5f / 6f + 1f); pole[1] = bl[1] - ay * rg * (6.5f / 6f + 1f); pole[2] = bl[2] - az * rg * (6.5f / 6f + 1f)
            val stream = t2Stream(); val q = FloatArray(3)
            val off = (secs * 1.5f) % 12f
            val fade = 1f - t2sm((tb - 0.75f) / 0.25f)
            for (w in 0..1) {
                val o = off - 12f * w
                q[0] = pole[0] - ax * o; q[1] = pole[1] - ay * o; q[2] = pole[2] - az * o
                t2Basis(q, -ax, -ay, -az, ux, uy, uz, 1f, 1f, 1f, stream, T2_PHAGE_HEAD, T2_PHAGE_HEAD, fade, 0.3f)
            }
            // those still inside, thinning out
            t2Basis(bl, ax, ay, az, ux, uy, uz, 1f, 1f, 1f, t2Progeny(), T2_PHAGE_HEAD, T2_PHAGE_HEAD, 1f - t2sm(tb / 0.6f), 0.3f)
            t2Basis(bl, ax, ay, az, ux, uy, uz, rg, rg, rg, t2Ghost(), T2_ECOLI, T2_ECOLI_OM, 0.6f - 0.45f * shrink, 0.1f)
        }
    }
}

// ============================================================================ stop 3: LIVER
// 8 µm a unit (Mote 12 µm). A sinusoid ~35 µm across, walled all round by hepatocytes (~25 µm
// polygonal cells, one per plate thickness, nuclei central, some binucleate) seen through the
// fenestrated endothelium and the space of Disse; bile canaliculi run between neighbouring
// hepatocytes at mid-depth, never facing the blood; stellate Kupffer cells squat on the lining.

private fun StereoBodyRenderer.t2LiverMeshes(i: Int): Array<ColorVboMesh> = t2Get("liver$i") {
    val opaque = T2Geo(); val glass = T2Geo(); val lining = T2Geo()
    val rf = T2_SIN_RF; val ro = 3.95f; val rm = 2.6f
    val cols = 4; val circ = 2f * T2PI * rf; val rh = circ / cols / 1.5f; val rowH = sqrt(3f) * rh
    fun cyl(arc: Float, z: Float, rho: Float): T2V { val th = arc / rf; return t2v(cos(th) * rho, sin(th) * rho, z) }
    val canal = HashSet<Long>()
    val rnd = java.util.Random(31L)
    var cellNo = 0
    val micro = ArrayList<Float>()
    for (row in -3..4) for (c in 0 until cols) {
        val ac = c * 1.5f * rh + 0.35f; val zc = row * rowH + (c % 2) * rowH * 0.5f + 1.2f
        if (zc < -6.5f || zc > 11.5f) continue
        cellNo++
        val ca = FloatArray(18); val cz = FloatArray(18)          // corners, each edge in 3
        val ra = FloatArray(18); val rz = FloatArray(18)          // unshrunk (shared edges coincide)
        for (k in 0 until 6) {
            val a0 = k * T2PI / 3f; val a1 = (k + 1) * T2PI / 3f
            for (q in 0 until 3) {
                val t = q / 3f
                val x = cos(a0) * (1 - t) + cos(a1) * t; val y = sin(a0) * (1 - t) + sin(a1) * t
                ra[k * 3 + q] = ac + rh * x; rz[k * 3 + q] = zc + rh * y
                ca[k * 3 + q] = ac + rh * 0.955f * x; cz[k * 3 + q] = zc + rh * 0.955f * y
            }
        }
        val face = T2_HEPATOCYTE
        for (m in 0 until 18) {
            val m1 = (m + 1) % 18
            // blood-facing face (translucent), back face (opaque), lateral walls (translucent)
            val c0 = cyl(ac, zc, rf); val mA = cyl(ac + (ca[m] - ac) * 0.5f, zc + (cz[m] - zc) * 0.5f, rf); val mB = cyl(ac + (ca[m1] - ac) * 0.5f, zc + (cz[m1] - zc) * 0.5f, rf)
            val oA = cyl(ca[m], cz[m], rf); val oB = cyl(ca[m1], cz[m1], rf)
            // a faint window over the nucleus, the rest of the blood face nearly opaque
            glass.tri(c0, mA, mB, face, 0.82f); glass.tri(mA, oA, oB, face, 0.85f); glass.tri(mA, oB, mB, face, 0.85f)
            val bA = cyl(ca[m], cz[m], ro); val bB = cyl(ca[m1], cz[m1], ro)
            opaque.tri(cyl(ac, zc, ro), cyl(ca[m], cz[m], ro), cyl(ca[m1], cz[m1], ro), T2_HEPATOCYTE_BACK)
            glass.quad(oA, oB, bB, bA, T2_HEPATOCYTE_BACK, 0.4f)
        }
        // nucleus (or two), central in the cell
        // nuclei inside the cell, only a soft shadow through the blood face
        if (cellNo % 4 == 0) for (sd in SIGNS) { glass.ball(cyl(ac + sd * 0.5f, zc, 2.3f), 0.4f, T2_HEP_NUC3, 0.5f, 9, 14); glass.ball(cyl(ac + sd * 0.5f, zc, 1.95f), 0.08f, T2_NUCLEOLUS, 0.5f, 4, 6) }
        else { glass.ball(cyl(ac, zc, 2.3f), 0.45f, T2_HEP_NUC3, 0.5f, 9, 14); glass.ball(cyl(ac, zc, 1.9f), 0.08f, T2_NUCLEOLUS, 0.5f, 4, 6) }
        // bile canaliculi: a belt along every shared lateral face, half-way through the plate
        for (k in 0 until 6) {
            val a0 = k * T2PI / 3f; val a1 = (k + 1) * T2PI / 3f
            val xa = ac + rh * cos(a0); val za = zc + rh * sin(a0); val xb = ac + rh * cos(a1); val zb = zc + rh * sin(a1)
            val key = (((xa + xb) * 50f).roundToLong() * 100003L) + ((za + zb) * 50f).roundToLong()
            if (!canal.add(key)) continue
            val pts = (0..4).map { q -> val t = q / 4f; cyl(xa + (xb - xa) * t, za + (zb - za) * t, rm) }
            opaque.path(pts, { 0.085f }, T2_CANALICULUS, 1f, 5, true)
        }
        // microvilli from the blood face into the space of Disse
        repeat(18) {
            val u = (rnd.nextFloat() * 2f - 1f) * rh * 0.8f; val w = (rnd.nextFloat() * 2f - 1f) * rh * 0.7f
            val a = cyl(ac + u, zc + w, rf - 0.005f); val b = cyl(ac + u, zc + w, rf - 0.06f)
            micro.addAll(listOf(a.x, a.y, a.z, 0.95f, 0.7f, 0.66f, 0.45f, b.x, b.y, b.z, 0.95f, 0.7f, 0.66f, 0.45f))
        }
    }
    // the sinusoidal endothelium: a thin translucent lining with flat nuclei, and fenestrae
    // (sub-micrometre holes, drawn as dark sieve-plate dots)
    val re = T2_SIN_RF - 0.07f
    lining.surf(40, 30, T2_ENDOTHELIUM, 0.35f) { v, u -> val a = u * 2f * T2PI; t2v(cos(a) * re, sin(a) * re, -6.5f + 18f * v) }
    repeat(8) {
        val a = rnd.nextFloat() * 2f * T2PI; val z = -4f + rnd.nextFloat() * 14f
        val c = t2v(cos(a) * (re - 0.03f), sin(a) * (re - 0.03f), z)
        lining.ell(c + t2v(cos(a), sin(a), 0f) * 0.02f, t2v(-sin(a), cos(a), 0f) * 0.22f, t2v(cos(a), sin(a), 0f) * 0.04f, t2v(0f, 0f, 0.6f), T2_ENDO_NUC, 0.45f, 8, 12)
    }
    // fenestrae: dark holes about 0.1 µm across, grouped in sieve plates
    val fen = ArrayList<Float>()
    repeat(70) {
        val a = rnd.nextFloat() * 2f * T2PI; val z = -5f + rnd.nextFloat() * 15f
        lining.disc(t2v(cos(a) * (re - 0.005f), sin(a) * (re - 0.005f), z), t2v(-cos(a), -sin(a), 0f), 0.25f, T2_SIEVE, 0.25f, 10)
        repeat(10) {   // the fenestrae: dots lying in the sieve plate
            val da = (rnd.nextFloat() - 0.5f) * 0.3f; val dz = (rnd.nextFloat() - 0.5f) * 0.3f
            lining.disc(t2v(cos(a + da) * (re - 0.008f), sin(a + da) * (re - 0.008f), z + dz), t2v(-cos(a + da), -sin(a + da), 0f), 0.02f, T2_FENESTRA, 1f, 5)
        }
        if (quality < 0) repeat(14) {
            val da = (rnd.nextFloat() - 0.5f) * 0.3f; val dz = (rnd.nextFloat() - 0.5f) * 0.35f
            fen.addAll(listOf(cos(a + da) * (re - 0.01f), sin(a + da) * (re - 0.01f), z + dz, 0.2f, 0.05f, 0.08f, 1f))
        }
    }
    // hepatic stellate (Ito) cells in the space of Disse, studded with vitamin-A lipid droplets,
    // their thin processes curling round the sinusoid
    val ito = T2Geo()
    for (sc in arrayOf(floatArrayOf(0.9f, 2.4f), floatArrayOf(3.6f, 7.4f))) {
        val a = sc[0]; val z = sc[1]; val rr = T2_SIN_RF - 0.03f
        val c = t2v(cos(a) * rr, sin(a) * rr, z)
        val rad = t2v(cos(a), sin(a), 0f); val tg = t2v(-sin(a), cos(a), 0f)
        ito.ell(c, tg * 0.9f, rad * 0.04f, t2v(0f, 0f, 0.6f), T2_STELLATE, 0.3f, 6, 10)
        for (d in 0 until 5) { val q = d * 1.26f; ito.ball(c + tg * (0.5f * cos(q)) + t2v(0f, 0f, 0.35f * sin(q)), 0.045f, T2_LIPID_DROP, 0.3f, 4, 6) }
        for (pr in 0 until 3) {
            val pts = (0..8).map { q -> val t = q / 8f; val aa = a + (pr - 1f) * 0.3f + (if (pr == 1) 1f else -1f) * t * 1.4f; t2v(cos(aa) * (T2_SIN_RF + 0.03f), sin(aa) * (T2_SIN_RF + 0.03f), z + (pr - 1f) * 0.6f * t) }
            ito.path(pts, { 0.015f }, T2_STELLATE, 0.3f, 4, true)
        }
    }
    // the two Kupffer cells further along (static): stellate macrophages on the lining, phagosomes inside
    for (kc in T2_KUPFFER_AT.drop(1)) t2KupfferInto(opaque, glass, kc[0], kc[1], kc[2] > 0.5f)
    // downstream the sinusoid opens into the central vein (the blood leaves the lobule there)
    opaque.surf(10, 28, T2_CENTRAL_VEIN) { v, u -> val a = u * 2f * T2PI; val r = T2_SIN_RF + 0.05f + 4.5f * t2sm(v); t2v(cos(a) * r, sin(a) * r, 11.2f + 2.4f * v) }
    lining.surf(6, 28, T2_ENDOTHELIUM, 0.35f) { v, u -> val a = u * 2f * T2PI; val r = re + 4.5f * t2sm(v); t2v(cos(a) * r, sin(a) * r, 11.2f + 2.4f * v) }
    val bend = t2Bend(i, 30f, 40f)
    val fenA = fen.toFloatArray(); for (k in 0 until fenA.size / 7) bend(fenA, k * 7)
    val micA = micro.toFloatArray(); for (k in 0 until micA.size / 7) bend(micA, k * 7)
    arrayOf(t2Bake(opaque, i, 30f, 40f), t2Bake(glass, i, 30f, 40f), t2Bake(lining, i, 30f, 40f), PointMesh(fenA), LineMesh(micA), t2Bake(ito, i, 30f, 40f))
}

/** Lumen radius of the sinusoid (the hepatocytes' blood faces): 20 µm across, red cells nearly in single file. */
private const val T2_SIN_RF = 1.25f
/** Kupffer cells: angle round the lining, position along the rail, 1 = one pseudopod bridges the lumen. */
private val T2_KUPFFER_AT = arrayOf(floatArrayOf(-0.95f, 3.4f, 0f), floatArrayOf(-2.45f, 6.6f, 1f), floatArrayOf(1.4f, 9.4f, 0f))

/**
 * A Kupffer cell on the lining at angle [a], [z] along: a body of three overlapping flattened lobes
 * (translucent), a kidney-shaped nucleus and four phagosomes (one holding a red-cell fragment)
 * inside, and six tapering pseudopods lying on the endothelium (one across the lumen if [bridge]).
 */
/** A flat tapered ribbon (a pseudopod lying on the sinusoid lining) along [pts], width w0 -> w1. */
private fun T2Geo.t2Ribbon(pts: List<T2V>, w0: Float, w1: Float, col: FloatArray) {
    val m = pts.size
    for (k in 0 until m - 1) {
        fun side(j: Int): T2V { val tg = (pts[min(j + 1, m - 1)] - pts[max(j - 1, 0)]).unit(); val rad = t2v(pts[j].x, pts[j].y, 0f).unit(); return (tg cross rad).unit() }
        val wa = w0 + (w1 - w0) * k / (m - 1f); val wb = w0 + (w1 - w0) * (k + 1) / (m - 1f)
        val sa = side(k); val sb = side(k + 1)
        val ra = t2v(pts[k].x, pts[k].y, 0f).unit() * -0.015f; val rb = t2v(pts[k + 1].x, pts[k + 1].y, 0f).unit() * -0.015f
        quad(pts[k] + sa * wa + ra, pts[k + 1] + sb * wb + rb, pts[k + 1] - sb * wb + rb, pts[k] - sa * wa + ra, col)
    }
}

private fun T2Geo.t2KupfferBody(glass: T2Geo, a: Float, z: Float) {
    val rr = T2_SIN_RF - 0.2f
    val rad = t2v(cos(a), sin(a), 0f); val tg = t2v(-sin(a), cos(a), 0f); val c = rad * rr + t2v(0f, 0f, z)
    // three lumpy lobes (9 x 5 x 7 µm) bulging 0.6 into the lumen, phagosomes pressing out under the surface
    for ((k, o) in arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0.4f, 0.35f), floatArrayOf(-0.35f, -0.3f)).withIndex())
        ell(c + tg * o[0] + t2v(0f, 0f, o[1]) - rad * 0.1f, tg * (0.55f - 0.07f * k), rad * (0.3f - 0.04f * k), t2v(0f, 0f, 0.45f - 0.05f * k), T2_KUPFFER, 1f, 7, 11)
    for (k in 0 until 4) { val q = 0.7f + k * 1.5f; ball(c + tg * (0.3f * cos(q)) + t2v(0f, 0f, 0.28f * sin(q)) - rad * 0.33f, 0.14f + 0.015f * k, T2_PHAGOSOME, 1f, 5, 7) }
    ell(c + tg * (0.3f * cos(0.7f)) + t2v(0f, 0f, 0.28f * sin(0.7f)) - rad * 0.45f, tg * 0.09f, rad * 0.03f, t2v(0f, 0f, 0.07f), COL_RBC_DEOXY, 1f, 4, 6)
}

private fun StereoBodyRenderer.t2KupfferInto(g: T2Geo, glass: T2Geo, a: Float, z: Float, bridge: Boolean) {
    g.t2KupfferBody(glass, a, z)
    val rr = T2_SIN_RF - 0.12f
    for (p in 0 until 6) {   // tapered pseudopods lying flat on the lining, mostly along the sinusoid
        val da = (p - 2.5f) * 0.14f; val dz = if (p % 2 == 0) 1.6f else -1.5f
        val pts = (0..8).map { q -> val t = q / 8f; val aa = a + da * t; t2v(cos(aa) * rr, sin(aa) * rr, z + dz * t) }
        g.t2Ribbon(pts, 0.12f, 0.02f, T2_KUPFFER)
    }
    if (bridge) {   // one pseudopod straight across the lumen to the opposite wall
        val p0 = t2v(cos(a) * rr, sin(a) * rr, z + 0.2f); val p1 = t2v(-cos(a + 0.4f) * rr, -sin(a + 0.4f) * rr, z + 1.2f)
        g.path(listOf(p0, (p0 + p1) * 0.5f, p1), { t -> 0.1f - 0.05f * t }, T2_KUPFFER, 1f, 5, true)
    }
}

/** The dynamic (feeding) Kupffer cell's body, as a single mesh at the lining (local frame at its node). */
private fun StereoBodyRenderer.t2KupfferMesh(): Array<TriMesh> = t2Get("kupffer0") {
    val g = T2Geo(); val glass = T2Geo(); g.t2KupfferBody(glass, 0f, 0f)
    // four flat pseudopods lying on the lining along the sinusoid (the two that hunt are drawn live)
    for (p in 2 until 6) {
        val da = (p - 2.5f) * 0.14f; val dzp = if (p % 2 == 0) 1.6f else -1.5f
        g.t2Ribbon((0..8).map { q -> val t = q / 8f; val aa = da * t; t2v(cos(aa) * (T2_SIN_RF - 0.1f), sin(aa) * (T2_SIN_RF - 0.1f), dzp * t) }, 0.12f, 0.02f, T2_KUPFFER)
    }
    arrayOf(TriMesh(g.baked()), TriMesh(glass.baked()))
}

/** Tour II stop 4: a liver sinusoid between hepatocyte plates, canaliculi between the cells, Kupffer cells on the lining. */
internal fun StereoBodyRenderer.drawLiver(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2LiverMeshes(i)
        t2DrawWorld(m[0])
        t2DrawWorld(m[4], false, 1f)
        t2DrawWorld(m[1], true)
        t2DrawWorld(m[2], true)
        t2DrawWorld(m[5], true)
        // the feeding Kupffer cell: its pseudopods reach out along the lining and close round a
        // worn-out red cell every 15 s, drawing it in
        val eat = (seconds % 15f) / 15f
        val kc = T2_KUPFFER_AT[0]; val ang = kc[0]; val z = kc[1]
        val fr = t2Frame(i, z)
        val km = t2KupfferMesh()
        t2Model(fr, 0f, 0f, 0f); Matrix.rotateM(model, 0, ang * 180f / T2PI, 0f, 0f, 1f)
        // (the mesh was built at angle 0 in the frame's x/y plane; rotate it round the rail axis)
        t2Draw(km[0]); t2Draw(km[1], true)
        val ca = cos(ang); val sa = sin(ang); val rr = T2_SIN_RF - 0.12f
        val rxw = fr[6] * ca + fr[9] * sa; val ryw = fr[7] * ca + fr[10] * sa; val rzw = fr[8] * ca + fr[11] * sa
        val b = t2W(fr, ca * (rr - 0.02f), sa * (rr - 0.02f)).copyOf()
        for (p in 0 until 2) {
            if (!(eat > 0.36f && eat < 0.88f)) break      // (at rest these two lie flat too; they rise to hunt)
            val da = (p - 2.5f) * 0.14f; val dzp = if (p % 2 == 0) 1.6f else -1.5f
            val pa = ang + da; val w = t2W(fr, cos(pa) * rr, sin(pa) * rr, dzp + 0.12f * sin(seconds * 0.7f + p))
            var gx = w[0]; var gy = w[1]; var gz = w[2]
            if (p < 2 && eat > 0.4f && eat < 0.85f) {   // pseudopods closing round the red cell
                val cw = t2W(fr, ca * 0.55f, sa * 0.55f, -0.75f)
                val s = t2sm((eat - 0.4f) / 0.15f); gx += (cw[0] - gx) * s; gy += (cw[1] - gy) * s; gz += (cw[2] - gz) * s
            }
            // a tapered pseudopod (cone: base at the cell, tip out along the lining)
            val dx = gx - b[0]; val dy = gy - b[1]; val dz = gz - b[2]; val ll = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
            t2Q[0] = (b[0] + gx) * 0.5f; t2Q[1] = (b[1] + gy) * 0.5f; t2Q[2] = (b[2] + gz) * 0.5f
            t2Basis(t2Q, dx / ll, dy / ll, dz / ll, rxw, ryw, rzw, 0.06f, 0.06f, ll * 0.5f, cone, T2_KUPFFER, T2_KUPFFER, 0.85f)
        }
        if (eat < 0.9f) {
            val arrive = t2sm(eat / 0.4f); val swallow = t2sm((eat - 0.6f) / 0.28f)
            val rz = -7f + (z - 0.75f + 7f) * arrive
            val fr2 = t2Frame(i, rz, t2G)
            val r2 = 0.45f + 0.1f * swallow
            val q = t2W(fr2, ca * r2, sa * r2, 0f).copyOf()
            val s = 0.46f * (1f - 0.7f * swallow)
            t2Basis(q, fr2[3], fr2[4], fr2[5], rxw, ryw, rzw, s, s, s, rbc, COL_RBC_DEOXY, COL_RBC_RIM, 1f - swallow * 0.8f)
        }
    }
}

// =========================================================================== stop 4: KIDNEY
// 8 µm a unit. The Mote rides Bowman's space, 25-40 µm wide here, between a glomerular tuft
// ~190 µm across (capillary loops 7 µm thick, grouped in lobules, clothed by podocytes with
// interdigitating foot processes) and the squamous parietal layer of the capsule. Filtrate beads
// leave the tuft and flow to the urinary pole, where the proximal tubule begins (cuboidal cells,
// brush border). The afferent and efferent arterioles enter at the vascular pole on the far side.

private val T2_TUFT = floatArrayOf(-14.5f, 0.5f, 7.5f)   // tuft centre (rigid node frame), radius 12
private val T2_CAPS = floatArrayOf(-14f, -3f, 0f)     // capsule centre, radius T2_CAPS_R
private const val T2_CAPS_R = 21f

/** The side of the tuft that faces the passage (-1: the tuft lies to starboard; +1: to port). */
private val T2_FACE = if (T2_TUFT[0] > 0f) -1f else 1f

/** Where the rail leaves Bowman's capsule (the urinary pole), in the node's rigid frame: x, y, z, arc position. */
private fun StereoBodyRenderer.t2UrinaryPole(i: Int): FloatArray = t2Get("upole$i") {
    val rail = t2Rail(i); val f0 = FloatArray(13); rail.at(0f, f0); val fr = FloatArray(13)
    var a = 0f; var out = floatArrayOf(0f, 0f, 10f, 10f)
    while (a < 30f) {
        rail.at(a, fr)
        val rx = fr[0] - f0[0]; val ry = fr[1] - f0[1]; val rz = fr[2] - f0[2]
        val x = rx * f0[6] + ry * f0[7] + rz * f0[8]; val y = rx * f0[9] + ry * f0[10] + rz * f0[11]; val z = rx * f0[3] + ry * f0[4] + rz * f0[5]
        val dx = x - T2_CAPS[0]; val dy = y - T2_CAPS[1]; val dz = z - T2_CAPS[2]
        if (dx * dx + dy * dy + dz * dz > T2_CAPS_R * T2_CAPS_R) { out = floatArrayOf(x, y, z, a); break }
        a += 0.05f
    }
    out
}

private fun StereoBodyRenderer.t2KidneyMeshes(i: Int): Array<ColorVboMesh> = t2Get("kidney$i") {
    val g = T2Geo(); val tub = T2Geo(); val rnd = java.util.Random(47L)
    val T = t2v(T2_TUFT[0], T2_TUFT[1], T2_TUFT[2]); val R = 12f
    val K = t2v(T2_CAPS[0], T2_CAPS[1], T2_CAPS[2]); val RC = T2_CAPS_R
    val up = t2UrinaryPole(i); val E = t2v(up[0], up[1], up[2])
    // ---- tuft core (mesangium and deeper loops) and ~110 capillary loops in six lobules
    g.ball(T, R - 1.6f, T2_MESANGIUM_D, 1f, 18, 26)
    val lobules = ArrayList<T2V>()
    while (lobules.size < 7) {
        val d = t2v(rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f)
        if (d.len() in 0.2f..1f && d.unit().x * T2_FACE > -0.2f) lobules.add(d.unit())
    }
    val nLoops = 290
    val glass = T2Geo()
    val front = ArrayList<List<T2V>>()      // loops facing the passage (for feet and blood)
    for (l in 0 until nLoops) {
        // half the loops cluster in lobules, the rest are spread evenly over the side facing the
        // passage, so no bare core shows between them
        val nrm = if (l % 2 == 0) (lobules[l % lobules.size] + t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * 0.55f).unit()
            else if (l >= 230) { var d: T2V; do { d = t2v(rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f) } while (d.len() > 1f || d.len() < 0.2f || d.unit().x * T2_FACE < 0.2f); d.unit() }
            else { var d: T2V; do { d = t2v(rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f) } while (d.len() > 1f || d.len() < 0.2f || d.unit().x * T2_FACE < -0.25f); d.unit() }
        val e1 = t2perp(nrm); val e2 = nrm cross e1
        val th = rnd.nextFloat() * 2f * T2PI; val dir = e1 * cos(th) + e2 * sin(th); val side = nrm cross dir
        val len = 2.8f + rnd.nextFloat() * 2.2f; val wid = 0.55f + rnd.nextFloat() * 0.3f; val layer = if (l >= 230) rnd.nextFloat() * 0.5f - 0.2f else rnd.nextFloat() * 0.9f - 0.2f
        val pts = ArrayList<T2V>()
        for (q in 0..22) {
            val t = q / 22f
            // a hairpin: out along one limb, round the bend, back along the other
            val (u, v) = when {
                t < 0.4f -> Pair(len * (t / 0.4f), -wid)
                t < 0.6f -> { val a = (t - 0.4f) / 0.2f * T2PI; Pair(len + wid * sin(a), -wid * cos(a)) }
                else -> Pair(len * (1f - (t - 0.6f) / 0.4f), wid)
            }
            val p0 = T + nrm * R + dir * (u - len * 0.5f) + side * v
            val rad = R + layer + 0.12f * sin(t * 3f * T2PI + l)
            pts.add(T + (p0 - T).unit() * rad)
        }
        val shade = 0.9f + 0.1f * rnd.nextFloat()
        val col = floatArrayOf(T2_CAPILLARY[0] * shade, T2_CAPILLARY[1] * shade, T2_CAPILLARY[2] * shade, 1f)
        val facing = nrm.x * T2_FACE > 0.45f && layer > 0.3f
        if (facing && front.size < 26) {
            front.add(pts)
            // the first ten are drawn see-through, with red cells passing through them
            if (front.size <= 10) glass.path(pts, { 0.42f }, T2_CAP_GLASS, 0.35f, 8, true) else g.path(pts, { 0.42f }, col, 1f, 8, true)
        } else g.path(pts, { 0.42f }, col, 1f, 8, true)
    }
    t2Get("kloops$i") { front.take(10) }
    // ---- podocytes: 8 large cells, each bulging into Bowman's space over the groove between 2-3
    //      front loops, primary processes arching over those loops and running along their flanks;
    //      from the processes of two neighbouring cells the foot processes wrap round each loop as
    //      C-shaped arcs, alternating every 0.09 units (the interdigitation; the filtration slits
    //      lie between them). Cells are coloured alternately.
    val wrapped = front.drop(3)
    val tops = wrapped.map { it[11] }
    val cells = ArrayList<T2V>()
    for (l in tops.indices) { if (cells.size >= 8) break; if (cells.all { (it - tops[l]).len() > 3.2f }) cells.add(tops[l]) }
    val cellPos = cells.map { c0 ->
        val near = tops.sortedBy { (it - c0).len() }.take(3); var m0 = t2v(0f, 0f, 0f); for (q in near) m0 = m0 + q
        val m1 = m0 * (1f / near.size); T + (m1 - T).unit() * (R + 1.3f)
    }
    for ((ci, cp) in cellPos.withIndex()) {
        val col = if (ci % 2 == 0) T2_PODOCYTE_A else T2_PODOCYTE_B
        val o = (cp - T).unit(); val e1 = t2perp(o)
        g.ellAxis(cp, o, 0.7f, 0.4f, 0.6f, col, 1f, 8, 12, e1)
        g.ball(cp + o * 0.1f, 0.3f, T2_HEP_NUC, 0.5f, 5, 7)
    }
    fun cellOf(p: T2V, not: Int = -1): Int { var best = 0; var bd = 1e9f; for ((ci, cp) in cellPos.withIndex()) { if (ci == not || (not >= 0 && ci % 2 == not % 2)) continue; val d = (cp - p).len(); if (d < bd) { bd = d; best = ci } }; return best }
    val nearLoops = wrapped.sortedBy { it[11].len() }.take(3).toSet()
    for (pts in wrapped) {
        val step = if (pts in nearLoops) 0.18f else 0.09f
        val samp = ArrayList<T2V>(); val tans = ArrayList<T2V>()
        var acc = 0f; var next = 0f
        for (q in 0 until pts.size - 1) {
            val a = pts[q]; val b = pts[q + 1]; val sl = (b - a).len()
            while (next <= acc + sl) { val t = (next - acc) / max(1e-5f, sl); samp.add(a + (b - a) * t); tans.add((b - a).unit()); next += step }
            acc += sl
        }
        if (cellPos.isEmpty()) break
        val ca = cellOf(pts[11]); val cb = cellOf(pts[11], ca)
        val colA = if (ca % 2 == 0) T2_PODOCYTE_A else T2_PODOCYTE_B; val colB = if (cb % 2 == 0) T2_PODOCYTE_A else T2_PODOCYTE_B
        val procA = ArrayList<T2V>(); val procB = ArrayList<T2V>()
        for ((k, c) in samp.withIndex()) {
            val tn = tans[k]; val oRaw = (c - T).unit(); val o = (oRaw - tn * (oRaw dot tn)).unit(); val b = tn cross o
            val a60 = 1.05f
            if (k % 3 == 0) { procA.add(c + (o * cos(a60) + b * sin(a60)) * 0.54f); procB.add(c + (o * cos(a60) - b * sin(a60)) * 0.54f) }
            val fg2 = if (pts in nearLoops) glass else g; val fal = if (pts in nearLoops) 0.6f else 1f
            if (k % 2 == 0) fg2.torus(c, tn, 0.47f, 0.035f, colA, fal, 4, 3, a60, -0.45f, o)
            else fg2.torus(c, tn, 0.47f, 0.035f, colB, fal, 4, 3, -a60, 0.45f, o)
        }
        if (procA.size > 1) g.path(procA, { 0.1f }, colA, 1f, 5, true)
        if (procB.size > 1) g.path(procB, { 0.1f }, colB, 1f, 5, true)
        // primary processes arching from each cell body over to this loop's flanks
        for ((cix, pr) in listOf(ca to procA, cb to procB)) {
            if (pr.size < 2) continue
            val cp = cellPos[cix]; val tgt = pr[pr.size / 2]; val mid = (cp + tgt) * 0.5f; val archTop = T + (mid - T).unit() * ((mid - T).len() + 0.5f)
            g.path(listOf(cp, archTop, tgt), { t -> 0.15f - 0.05f * t }, if (cix % 2 == 0) T2_PODOCYTE_A else T2_PODOCYTE_B, 1f, 6, true)
        }
    }
    // ---- the vascular pole, on the side we face (upper left): the afferent arteriole (20 µm) and
    //      the narrower efferent arteriole enter the tuft through a sleeve of the capsule; a cuff of
    //      granular juxtaglomerular cells (they make renin) on the afferent, and the macula densa, a
    //      plaque of tall crowded cells in the distal tubule that touches the pole.
    // (placed about 30 degrees up and 30 degrees to port of straight ahead from the stop)
    // (on the tuft's top, where it hangs from the capsule roof by its stalk, in the upper left of view:
    //  the arterioles run inside the stalk, sheathed by the parietal layer turning onto the tuft)
    val vdir = t2v(0.91f, 0.34f, 0.24f).unit(); val ve1 = t2perp(vdir); val ve2 = vdir cross ve1
    val tIn = T + vdir * (R - 0.8f)
    var tOut = R + 2f; run { var d = R; while (d < 40f) { if ((T + vdir * d - K).len() > RC) { tOut = d; break }; d += 0.1f } }
    // a 100-degree window cut in the sheath facing the lane (over the middle 60% of the stalk), and
    // behind it the two arterioles side by side across the line of sight, the cuff on the afferent
    val mid0 = T + vdir * ((R + tOut) * 0.5f)
    val lane = (t2v(0f, 0f, 0f) - mid0).let { (it - vdir * (it dot vdir)).unit() }
    val side = (vdir cross lane).unit()
    val pa0 = R - 0.8f; val pa1 = tOut + 1.5f
    fun flare(d: Float) = 1f + 0.9f * ((d - pa0) / (pa1 - pa0)).pow(2)
    for ((k, rr) in floatArrayOf(1.25f, 0.85f).withIndex()) {
        val off = side * (if (k == 0) 0.6f else -0.55f)
        g.path((0..8).map { q -> val t = q / 8f; T + vdir * (pa0 + (pa1 - pa0) * t) + off * (1f + 0.9f * t * t) }, { rr * 0.72f }, if (k == 0) T2_ARTERIOLE else T2_EFFERENT, 1f, 10, true)
    }
    // the stalk's sheath: the capsule wall flaring down from the pole onto the tuft
    val sh0 = R - 0.2f; val shL = tOut + 0.1f - sh0
    val sheath = T2Geo()
    sheath.surf(10, 36, T2_STALK) { v, u ->
        val a = u * 2f * T2PI; val d = sh0 + shL * v
        val fl = t2sm((d - (tOut - 1.5f)) / 1.5f); val r = 2.1f + 1.5f * fl * fl
        T + vdir * d + (ve1 * cos(a) + ve2 * sin(a)) * r
    }
    val winCos = cos(50f * T2PI / 180f)
    g.appendWhere(sheath) { x, y, z ->
        val q = t2v(x, y, z) - T; val d = q dot vdir; val rad = (q - vdir * d).unit()
        !(d > sh0 + shL * 0.2f && d < sh0 + shL * 0.8f && (rad dot lane) > winCos)
    }
    run {   // the juxtaglomerular cuff: granular cells (they make renin) set in the afferent's wall, a
            // continuous collar 1.2 units long in the window, renin granules seen inside each cell
        val jr = java.util.Random(71L); val zMid = sh0 + shL * 0.5f
        for (ring in 0 until 2) for (k in 0 until 7) {
            val a = (k + 0.5f * ring) * 2f * T2PI / 7f; val zAlong = zMid + (ring - 0.5f) * 0.6f
            val cc = T + vdir * zAlong + side * 0.6f * flare(zAlong)
            val rdir = lane * cos(a) + side * sin(a); val tdir = vdir cross rdir
            val c = cc + rdir * 0.98f
            glass.ell(c, tdir * 0.45f, rdir * 0.35f, vdir * 0.4f, T2_JG, 0.6f, 6, 9)
            for (q in 0 until 5) {
                val u = (jr.nextFloat() - 0.5f) * 1.1f; val w = (jr.nextFloat() - 0.5f) * 1.0f; val h = (jr.nextFloat() - 0.5f) * 0.9f
                g.ball(c + tdir * (0.45f * u) + rdir * (0.35f * w) + vdir * (0.4f * h), 0.06f, T2_JG_GRANULE, 1f, 3, 4)
            }
        }
    }
    run {   // the distal tubule touching the pole, its macula densa plaque facing the arterioles
        val c0 = T + vdir * (tOut + 0.3f) + ve2 * 2.4f
        g.path(listOf(c0 - ve1 * 5f, c0 + ve1 * 5f), { 1.2f }, T2_DISTAL, 1f, 12, true)
        for (k in 0 until 12) {
            val x = (k - 5.5f) * 0.42f; val base = c0 + ve1 * x - ve2 * 1.0f
            g.box(base - ve2 * 0.6f, ve1 * 0.2f, ve2 * 0.6f, vdir * 0.25f, T2_MACULA)
            g.ball(base - ve2 * 1.0f, 0.15f, T2_HEP_NUC, 1f, 4, 6)
        }
    }
    // ---- Bowman's capsule: parietal layer (simple squamous), open at the urinary pole
    val ue = (E - K).unit(); val holeCos = cos(asin(3.1f / RC))
    val ce1 = t2perp(ue); val ce2 = ue cross ce1
    // (open also at the vascular pole, where the arterioles pass and the macula densa sits)
    val vPole = K + (T + vdir * tOut - K).unit() * RC
    val capG = T2Geo()
    capG.surf(40, 56, T2_PARIETAL) { u, v ->
        val th = acos(holeCos) + u * (T2PI - acos(holeCos)); val ph = v * 2f * T2PI
        K + (ue * cos(th) + (ce1 * cos(ph) + ce2 * sin(ph)) * sin(th)) * RC
    }
    g.appendWhere(capG) { x, y, z -> (t2v(x, y, z) - vPole).len() > 3.4f }
    val lines = ArrayList<Float>()
    val lat = 16; val lon = 34
    val grid = Array(lat + 1) { la -> Array(lon) { lo ->
        val th = acos(holeCos) + 0.1f + (la + 0.5f * ((lo + la) % 2) * 0.4f) / lat * (T2PI - acos(holeCos) - 0.1f); val ph = (lo + 0.5f * (la % 2) + (rnd.nextFloat() - 0.5f) * 0.3f) / lon * 2f * T2PI
        K + (ue * cos(th) + (ce1 * cos(ph) + ce2 * sin(ph)) * sin(th)) * (RC - 0.06f)
    } }
    fun ln(a: T2V, b: T2V) { lines.addAll(listOf(a.x, a.y, a.z, 1f, 0.9f, 0.86f, 0.5f, b.x, b.y, b.z, 1f, 0.9f, 0.86f, 0.5f)) }
    for (la in 0..lat) for (lo in 0 until lon) {
        if ((grid[la][lo] - vPole).len() < 3.8f) continue
        ln(grid[la][lo], grid[la][(lo + 1) % lon])
        if (la < lat) ln(grid[la][lo], grid[la + 1][lo])
        if ((la * 3 + lo) % 5 == 0) {
            val c = (grid[la][lo] + grid[la][(lo + 1) % lon]) * 0.5f
            g.ellAxis(c + (K - c).unit() * 0.02f, (K - c).unit(), 0.5f, 0.03f, 0.35f, T2_PARIETAL_NUC, 1f, 4, 7)
        }
    }
    // ---- the proximal tubule leaving the urinary pole along the rail: cuboidal cells with round
    //      nuclei and a dense brush border lining a lumen ~20 µm across.
    val brush = ArrayList<Float>()
    val a0 = up[3] - 0.6f
    val nc = 11
    for (row in 0 until 7) {
        val zc = a0 + row * 1.35f
        for (c in 0 until nc) {
            val th0 = (c + 0.5f * (row % 2)) * 2f * T2PI / nc; val th1 = th0 + 2f * T2PI / nc * 0.95f
            val z0 = zc; val z1 = zc + 1.3f
            fun P(th: Float, z: Float, r: Float) = t2v(cos(th) * r, sin(th) * r, z)
            tub.quad(P(th0, z0, 1.6f), P(th1, z0, 1.6f), P(th1, z1, 1.6f), P(th0, z1, 1.6f), T2_TUBULE)
            tub.quad(P(th0, z0, 1.6f), P(th0, z0, 3.0f), P(th0, z1, 3.0f), P(th0, z1, 1.6f), T2_PCT_SIDE)
            tub.quad(P(th0, z0, 1.6f), P(th1, z0, 1.6f), P(th1, z0, 3.0f), P(th0, z0, 3.0f), T2_PCT_SIDE)
            tub.ball(P((th0 + th1) * 0.5f, zc + 0.65f, 2.45f), 0.42f, T2_NUCLEUS, 1f, 5, 8)
            repeat(14) {
                val th = th0 + (th1 - th0) * rnd.nextFloat(); val z = z0 + 1.3f * rnd.nextFloat()
                brush.addAll(listOf(cos(th) * 1.6f, sin(th) * 1.6f, z, 0.99f, 0.88f, 0.84f, 0.85f, cos(th) * 1.36f, sin(th) * 1.36f, z, 0.99f, 0.88f, 0.84f, 0.85f))
            }
        }
    }
    val rigid = t2Bend(i, 0f, 0f); val bent = t2Bend(i, 30f, 40f)
    val lA = lines.toFloatArray(); for (k in 0 until lA.size / 7) rigid(lA, k * 7)
    val bA = brush.toFloatArray(); for (k in 0 until bA.size / 7) bent(bA, k * 7)
    arrayOf(TriMesh(g.baked(rigid)), LineMesh(lA), TriMesh(tub.baked(bent)), LineMesh(bA), TriMesh(glass.baked(rigid)))
}

/** Tour II stop 5: inside Bowman's capsule beside a glomerular tuft, filtrate flowing to the proximal tubule. */
internal fun StereoBodyRenderer.drawKidney(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val m = t2KidneyMeshes(i)
        t2DrawWorld(m[0]); t2DrawWorld(m[2]); t2DrawWorld(m[1], true); t2DrawWorld(m[3], true)
        // red cells squeezing through the see-through loops (0.6 units/s)
        val f00 = t2Rigid(i, 0f, FloatArray(13))
        @Suppress("UNCHECKED_CAST") val loops = t2Get("kloops$i") { emptyList<List<T2V>>() }
        for ((li, pts) in loops.withIndex()) for (c in 0..2) {
            val n = pts.size - 1; var total = 0f; for (q in 0 until n) total += (pts[q + 1] - pts[q]).len()
            var sPos = ((seconds * 0.6f + c * total / 3f + li * 1.7f) % total); var q = 0
            while (q < n - 1 && sPos > (pts[q + 1] - pts[q]).len()) { sPos -= (pts[q + 1] - pts[q]).len(); q++ }
            val a = pts[q]; val b = pts[q + 1]; val tt = (sPos / max(1e-4f, (b - a).len())).coerceIn(0f, 1f)
            val pp = a + (b - a) * tt; val tn = (b - a).unit(); val pr = t2perp(tn)
            val w = t2W(f00, pp.x, pp.y, pp.z).copyOf()
            val tx = f00[6] * tn.x + f00[9] * tn.y + f00[3] * tn.z; val ty = f00[7] * tn.x + f00[10] * tn.y + f00[4] * tn.z; val tz = f00[8] * tn.x + f00[11] * tn.y + f00[5] * tn.z
            val px = f00[6] * pr.x + f00[9] * pr.y + f00[3] * pr.z; val py = f00[7] * pr.x + f00[10] * pr.y + f00[4] * pr.z; val pz = f00[8] * pr.x + f00[11] * pr.y + f00[5] * pr.z
            t2Basis(w, px, py, pz, tx, ty, tz, 0.36f, 0.36f, 0.36f, rbc, COL_RBC_OXY, COL_RBC_RIM)
        }
        t2DrawWorld(m[4], true)
        // filtrate: plasma water squeezed out between the podocyte feet into Bowman's space,
        // drifting to the urinary pole and down the tubule
        val f0 = t2Rigid(i, 0f, FloatArray(13)); val up = t2UrinaryPole(i)
        t2LinesBegin()
        val cnt = if (quality == 0) 70 else 35
        for (k in 0 until cnt) {
            val t = ((seconds * 0.07f + k * 0.6180339f) % 1f)
            val zs = -1f + (k * 7.31f % 13f); val ys = -3f + (k * 3.7f % 6f)
            val dz = zs - T2_TUFT[2]; val dy = ys - T2_TUFT[1]
            val xs = T2_TUFT[0] + T2_FACE * (sqrt(max(1f, 144f - dz * dz - dy * dy)) + 0.8f)
            var x: Float; var y: Float; var z: Float
            val x1 = xs + T2_FACE * 1.2f
            if (t < 0.15f) { val s = t / 0.15f; x = xs + T2_FACE * 1.2f * s; y = ys; z = zs }
            else { val s = t2sm((t - 0.15f) / 0.7f); x = x1 + (up[0] - x1) * s; y = ys + (up[1] - ys) * s; z = zs + (up[2] - zs) * s }
            val w = if (t > 0.85f) { val fr = t2Frame(i, up[3] + (t - 0.85f) / 0.15f * 6f); t2W(fr, 0.7f * sin(k * 1.3f), 0.7f * cos(k * 1.3f)) } else t2W(f0, x, y, z)
            t2Vert(w[0], w[1], w[2], T2_FILTRATE, 0.9f * t2sm(t / 0.05f))
        }
        t2LinesEnd(1f, true, 5f)
    }
}

// =========================================================================== stop 5: MUSCLE
// 0.8 µm a unit (Mote 1.2 µm). The Mote flies in the sarcoplasm between myofibrils (1.4 µm
// across) that run to the horizon, all in register. Each sarcomere is built from rigid parts:
// the thick-filament unit (myosin, 1.6 µm, bipolar with heads and a bare zone, the M line, the
// triads at each A-I junction) and the Z unit (Z disc with the 1.0 µm actin filaments of both
// neighbouring half-sarcomeres, mitochondria at the I band). On each twitch the sarcomere
// shortens from 2.4 to 1.9 µm by moving the units together: filament lengths never change, so
// the A band keeps its width while the I band and H zone narrow — the sliding filaments.

private const val T2_THICK_HALF = 1.0f   // 1.6 µm thick filament
private const val T2_THIN_LEN = 1.25f    // 1.0 µm thin filament from each Z disc

private fun t2Fibrils(): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    // the four beside and below the craft are opened up to show their filaments; the two above are drawn banded
    for (k in 0 until 6) { val a = k * T2PI / 3f; out.add(floatArrayOf(cos(a) * 2.7f, sin(a) * 2.7f, if (k == 1 || k == 2) 0f else 1f)) }
    for (k in 0 until 8) { val a = (k + 0.5f) * T2PI / 4f; out.add(floatArrayOf(cos(a) * 4.75f, sin(a) * 4.75f, 0f)) }
    return out
}

/** Thick-filament positions of a detailed myofibril: a hexagonal lattice, 2 shells (19 filaments) at spacing [d]. */
private fun t2ThickLattice(d: Float): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    for (q in -2..2) for (r in -2..2) { if (abs(q + r) > 2) continue; out.add(floatArrayOf(d * (q + r * 0.5f), d * r * sqrt(3f) * 0.5f)) }
    return out
}

/** Thin-filament positions: the trigonal points of the lattice (each thick filament ringed by six thin). */
private fun t2ThinLattice(d: Float, maxR: Float): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    for (p in t2ThickLattice(d)) for (k in 0 until 6) {
        val a = T2PI / 6f + k * T2PI / 3f; val x = p[0] + cos(a) * d / sqrt(3f); val y = p[1] + sin(a) * d / sqrt(3f)
        if (x * x + y * y > maxR * maxR) continue
        if (out.any { (it[0] - x) * (it[0] - x) + (it[1] - y) * (it[1] - y) < 0.01f }) continue
        out.add(floatArrayOf(x, y))
    }
    return out
}

/**
 * [0] thick unit without heads (centred on the M line), [1] Z unit (centred on the Z disc), [2] SR
 * for the calcium flash, [3] translucent Z-disc plates, [4..15] myosin heads: 3 groups x 4 stroke
 * poses (0 = perpendicular, 3 = swung 0.07 toward the M line), [16] alpha-actinin zig-zag (lines).
 */
private fun StereoBodyRenderer.t2MuscleMeshes(): Array<ColorVboMesh> = t2Get("muscle") {
    val th = T2Geo(); val zu = T2Geo(); val sr = T2Geo(); val zg = T2Geo(); val thin = T2Geo()
    val heads = Array(12) { T2Geo() }; val act = ArrayList<Float>()
    val zAx = t2v(0f, 0f, 1f); val d = 0.4f
    val headOn = t2mix(T2_MYOSIN_HEAD, T2_ENAMEL, 0.25f)
    for (fb in t2Fibrils()) {
        if (fb[2] < 0.5f) continue
        val cx = fb[0]; val cy = fb[1]
        for ((pi, p) in t2ThickLattice(d).withIndex()) {
            val x = cx + p[0]; val y = cy + p[1]
            th.tube(t2v(x, y, -T2_THICK_HALF), t2v(x, y, T2_THICK_HALF), 0.04f, 0.04f, T2_MYOSIN, 1f, 4, false)
            // myosin heads in crowns on both halves (a bare zone at the M line), each reaching for
            // one of the six thin filaments round its thick filament
            for (sgn in SIGNS) for (c in 0 until 4) {
                val z = sgn * (0.2f + c * 0.2f)
                for (h in 0 until 2) {
                    val a = T2PI / 6f + ((c * 2 + h * 3 + pi) % 6) * T2PI / 3f
                    val dx = cos(a); val dy = sin(a)
                    val grp = (pi * 7 + c * 3 + h + (if (sgn > 0f) 1 else 0)) % 3
                    for (pose in 0 until 4) {
                        val swing = -sgn * 0.07f * pose / 3f
                        heads[grp * 4 + pose].tube(t2v(x + dx * 0.04f, y + dy * 0.04f, z), t2v(x + dx * 0.12f, y + dy * 0.12f, z + swing * 0.8f), 0.022f, 0.02f, if (pose == 0) T2_MYOSIN_HEAD else headOn, 1f, 3, false)
                    }
                }
            }
        }
        // M line: a ring and spokes cross-linking the thick filaments at the centre of the A band
        // M line: short cross-links between neighbouring thick filaments, a hexagonal net (no rim)
        val lat = t2ThickLattice(d)
        for (p1 in lat.indices) for (p2 in p1 + 1 until lat.size) {
            val a1 = lat[p1]; val a2 = lat[p2]
            if (abs(sqrt((a1[0] - a2[0]).pow(2) + (a1[1] - a2[1]).pow(2)) - d) < 0.01f)
                th.tube(t2v(cx + a1[0], cy + a1[1], 0f), t2v(cx + a2[0], cy + a2[1], 0f), 0.018f, 0.018f, T2_MLINE, 1f, 3, false)
        }
        // the triads (a T tubule between two terminal cisternae at each A-I junction) and the
        // longitudinal SR, lit up as calcium floods out on each twitch
        // (only on the half of each fibril facing the lane, so the filament lattice stays in view)
        val face = atan2(-cy, -cx); val xAx = t2v(1f, 0f, 0f)
        for (sgn in SIGNS) { sr.torus(t2v(cx, cy, sgn * 1f), zAx, 1.02f, 0.03f, T2_TTUBULE, 1f, 12, 3, face - 1.4f, face + 1.4f, xAx); sr.torus(t2v(cx, cy, sgn * 0.9f), zAx, 1.03f, 0.03f, T2_CALCIUM, 1f, 12, 4, face - 1.4f, face + 1.4f, xAx); sr.torus(t2v(cx, cy, sgn * 1.1f), zAx, 1.03f, 0.03f, T2_CALCIUM, 1f, 12, 4, face - 1.4f, face + 1.4f, xAx) }
        for (k in 0 until 2) { val a = face + (k - 0.5f) * 1.6f; sr.tube(t2v(cx + cos(a) * 1.03f, cy + sin(a) * 1.03f, -0.9f), t2v(cx + cos(a) * 1.03f, cy + sin(a) * 1.03f, 0.9f), 0.025f, 0.025f, T2_CALCIUM, 1f, 4, false) }
        // Z disc: a thin plate (translucent) where the thin filaments of neighbouring sarcomeres
        // end, their ends cross-linked by alpha-actinin in a zig-zag lattice
        zg.slab(t2v(cx, cy, 0f), zAx, 0.98f, 0.1f, T2_ZDISC, 0.15f, 24)
        val thinA = t2ThinLattice(d, 0.92f)
        val thinB = thinA.map { q -> val r = sqrt(q[0] * q[0] + q[1] * q[1]); val a = atan2(q[1], q[0]) + 0.26f; floatArrayOf(cos(a) * r, sin(a) * r) }
            .filter { it[0] * it[0] + it[1] * it[1] < 0.95f * 0.95f }
        for (q in thinA) thin.tube(t2v(cx + q[0], cy + q[1], 0.04f), t2v(cx + q[0], cy + q[1], T2_THIN_LEN), 0.02f, 0.02f, T2_ACTIN_PALE, 0.6f, 3, false)
        for (q in thinB) thin.tube(t2v(cx + q[0], cy + q[1], -T2_THIN_LEN), t2v(cx + q[0], cy + q[1], -0.04f), 0.02f, 0.02f, T2_ACTIN_PALE, 0.6f, 3, false)
        for (a in thinA) for (b in thinB) {
            val dd = (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
            if (dd < 0.2f * 0.2f) act.addAll(listOf(cx + a[0], cy + a[1], 0.04f, 0.98f, 0.9f, 0.5f, 0.3f, cx + b[0], cy + b[1], -0.04f, 0.98f, 0.9f, 0.5f, 0.3f))
        }
    }
    // mitochondria: elongated, wrapped round the myofibrils at the I band, in pairs either side of
    // each Z line, on the side of each inner fibril that faces the lane
    for (fb in t2Fibrils()) {
        val r0 = sqrt(fb[0] * fb[0] + fb[1] * fb[1]); if (r0 < 3f) continue
        val face = atan2(-fb[1], -fb[0])
        for (sgn in SIGNS) {   // a thin curved plate wrapped round the fibril at the I band
            zu.surf(10, 8, T2_MITO_PALE) { u, v ->
                val a = face + (u - 0.5f) * 1.2f; val b = v * 2f * T2PI; val taper = sqrt(max(0.05f, sin(T2PI * u)))
                val r = 0.96f + 0.035f * cos(b) * taper
                t2v(fb[0] + cos(a) * r, fb[1] + sin(a) * r, sgn * 0.35f + 0.14f * sin(b) * taper)
            }
        }
    }
    val lines = LineMesh(act.toFloatArray())
    (arrayOf<ColorVboMesh>(TriMesh(th.baked()), TriMesh(zu.baked()), TriMesh(sr.baked()), TriMesh(zg.baked())) + heads.map { TriMesh(it.baked()) } + arrayOf<ColorVboMesh>(lines, TriMesh(thin.baked())))
}

/** The outer myofibrils as smooth 16-sided tubes following the rail; attributes: position, shade, arc z. */
private fun StereoBodyRenderer.t2BandMesh(i: Int): T2BandMesh = t2Get("bandmesh$i") {
    val out = ArrayList<Float>(); val sides = 16; val r = 0.9f
    val fibs = t2Fibrils().filter { it[2] < 0.5f }
    val fa = FloatArray(13); val fb = FloatArray(13); val step = 0.25f
    var z = -9f
    while (z < 17f) {
        t2Frame(i, z, fa); t2Frame(i, z + step, fb)
        for (f in fibs) for (sd in 0 until sides) {
            val q0 = sd * 2f * T2PI / sides; val q1 = (sd + 1) * 2f * T2PI / sides
            for ((fr, q, zz) in listOf(Triple(fa, q0, z), Triple(fb, q0, z + step), Triple(fb, q1, z + step), Triple(fa, q0, z), Triple(fb, q1, z + step), Triple(fa, q1, z))) {
                val cq = cos(q); val sq = sin(q); val x = f[0] + cq * r; val y = f[1] + sq * r
                val sh = 0.5f + 0.55f * abs(cq * 0.3f + sq * 0.83f)
                out.addAll(listOf(fr[0] + fr[6] * x + fr[9] * y, fr[1] + fr[7] * x + fr[10] * y, fr[2] + fr[8] * x + fr[11] * y, sh, zz))
            }
        }
        z += step
    }
    T2BandMesh(out.toFloatArray())
}

/** Banded-fibril mesh: the fragment shader colours Z line, I band, overlap, H zone and M line from the live sarcomere length. */
private class T2BandMesh(data: FloatArray) {
    val vbo = makeVbo(data); val count = data.size / 5
    companion object {
        var program = 0; var owner: Any? = null
        var hMvp = 0; var hL = 0; var hRef = 0; var hFade = 0; var aPos = 0; var aSh = 0; var aZ = 0
    }
    fun draw(owner0: Any, mvp: FloatArray, L: Float, zRef: Float, fade: Float) {
        if (owner !== owner0) {
            program = compileProgram(
                "attribute vec3 aPos; attribute float aSh; attribute float aZ; uniform mat4 uMvp; varying float vSh; varying float vZ;\n" +
                "void main() { vSh = aSh; vZ = aZ; gl_Position = uMvp * vec4(aPos, 1.0); }",
                FRAG_PRECISION + "uniform float uL; uniform float uRef; uniform float uFade; varying float vSh; varying float vZ;\n" +
                "void main() {\n" +
                "  float zz = vZ - uRef; float u = zz - floor(zz / uL) * uL; float m = uL * 0.5;\n" +
                "  vec3 c;\n" +
                "  if (u < 0.05 || u > uL - 0.05) c = vec3(0.96, 0.88, 0.52);\n" +
                "  else if (abs(u - m) > 1.0) c = vec3(0.97, 0.88, 0.82);\n" +
                "  else if (abs(u - m) < 0.03) c = vec3(0.6, 0.22, 0.3);\n" +
                "  else if (u < 1.25 || u > uL - 1.25) c = vec3(0.6, 0.17, 0.24);\n" +
                "  else c = vec3(0.82, 0.42, 0.46);\n" +
                "  gl_FragColor = vec4(min(c * vSh, vec3(1.0)), uFade);\n" +
                "}")
            hMvp = GLES20.glGetUniformLocation(program, "uMvp"); hL = GLES20.glGetUniformLocation(program, "uL"); hRef = GLES20.glGetUniformLocation(program, "uRef"); hFade = GLES20.glGetUniformLocation(program, "uFade")
            aPos = GLES20.glGetAttribLocation(program, "aPos"); aSh = GLES20.glGetAttribLocation(program, "aSh"); aZ = GLES20.glGetAttribLocation(program, "aZ")
            owner = owner0
        }
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(hMvp, 1, false, mvp, 0); GLES20.glUniform1f(hL, L); GLES20.glUniform1f(hRef, zRef); GLES20.glUniform1f(hFade, fade)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, 0); GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aSh, 1, GLES20.GL_FLOAT, false, 20, 12); GLES20.glEnableVertexAttribArray(aSh)
        GLES20.glVertexAttribPointer(aZ, 1, GLES20.GL_FLOAT, false, 20, 16); GLES20.glEnableVertexAttribArray(aZ)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aSh); GLES20.glDisableVertexAttribArray(aZ)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}

private val t2BandTris by lazy { DynMesh(100000) }
private var t2BandL = -1f
private var t2BandT = -1f
private var t2BandVerts = 0
private val T2_BAND_I = floatArrayOf(0.97f, 0.88f, 0.82f, 1f)
private val T2_BAND_OVL = floatArrayOf(0.6f, 0.17f, 0.24f, 1f)
private val T2_BAND_H = floatArrayOf(0.82f, 0.42f, 0.46f, 1f)

/**
 * The outer ring of myofibrils as banded cylinders, rebuilt (~15 Hz) whenever the sarcomere
 * length changes: Z line, I band (actin only, pale), overlap (actin + myosin, darkest), H zone
 * (myosin only), M line. The A band (overlap + H + overlap) is always 1.6 µm; only the I band and
 * H zone change as the fibre shortens.
 */
private fun StereoBodyRenderer.t2Bands(i: Int, L: Float, zRef: Float, seconds: Float) {
    if (abs(L - t2BandL) < 0.002f && t2BandVerts > 0) return
    if (t2BandVerts > 0 && abs(seconds - t2BandT) < 0.18f) return
    t2BandL = L; t2BandT = seconds
    val d = t2BandTris.data; var v = 0
    val fibs = t2Fibrils().filter { it[2] < 0.5f }
    val sides = 8; val r = 0.9f
    val fa = FloatArray(13); val fb = FloatArray(13)
    fun seg(z0: Float, z1: Float, c: FloatArray) {
        val a0 = max(z0, -9f); val a1 = min(z1, 17f); if (a1 <= a0) return
        t2Frame(i, a0, fa); t2Frame(i, a1, fb)
        for (f in fibs) for (s in 0 until sides) {
            if (v + 6 > 100000) return
            val q0 = s * 2f * T2PI / sides; val q1 = (s + 1) * 2f * T2PI / sides
            for ((fr, q) in arrayOf(fa to q0, fb to q0, fb to q1, fa to q0, fb to q1, fa to q1)) {
                val cq = cos(q); val sq = sin(q); val x = f[0] + cq * r; val y = f[1] + sq * r
                val sh = 0.5f + 0.55f * abs(cq * 0.3f + sq * 0.83f)
                val o = v * 7
                d[o] = fr[0] + fr[6] * x + fr[9] * y; d[o + 1] = fr[1] + fr[7] * x + fr[10] * y; d[o + 2] = fr[2] + fr[8] * x + fr[11] * y
                d[o + 3] = min(1f, c[0] * sh); d[o + 4] = min(1f, c[1] * sh); d[o + 5] = min(1f, c[2] * sh); d[o + 6] = 1f
                v++
            }
        }
    }
    val kMin = floor((-9f - zRef) / L).toInt() - 1; val kMax = ceil((17f - zRef) / L).toInt()
    for (k in kMin..kMax) {
        val z = zRef + k * L; val zn = z + L; val m = z + L * 0.5f
        seg(z - 0.05f, z + 0.05f, T2_ZDISC)
        seg(z + 0.05f, m - T2_THICK_HALF, T2_BAND_I)
        val o1 = min(z + T2_THIN_LEN, m); val o2 = max(zn - T2_THIN_LEN, m)
        seg(m - T2_THICK_HALF, o1, T2_BAND_OVL)
        if (o2 - o1 > 0.08f) { seg(o1, m - 0.03f, T2_BAND_H); seg(m - 0.03f, m + 0.03f, T2_MLINE); seg(m + 0.03f, o2, T2_BAND_H) }
        else seg(o1, o2, T2_BAND_OVL)
        seg(o2, m + T2_THICK_HALF, T2_BAND_OVL)
        seg(m + T2_THICK_HALF, zn - 0.05f, T2_BAND_I)
    }
    t2BandVerts = v
}

/** Sarcomere length (units) and calcium flash for a twitch every 6 s. */
private fun t2SarcL(seconds: Float): Float {
    val t = seconds % 6f
    val c = t2sm((t - 0.2f) / 0.45f) * (1f - t2sm((t - 2.2f) / 1.0f))
    return 3.0f - 0.6f * c        // 2.4 µm relaxed -> 1.9 µm contracted
}
private fun t2Flash(seconds: Float): Float {
    val t = seconds % 6f
    return if (t < 1.4f) t2sm(t / 0.15f) * (1f - t2sm((t - 0.5f) / 0.8f)) else 0f
}

/** Tour II stop 6: myofibrils in register, sliding filaments on every twitch. */
internal fun StereoBodyRenderer.drawMuscle(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2MuscleMeshes()
        val L = t2SarcL(seconds); val flash = t2Flash(seconds)
        val zRef = 1.0f     // this Z line holds still; the fibre shortens toward it
        val tw = seconds % 6f; val rowing = tw > 0.2f && tw < 2.2f
        val kMin = floor((-9f - zRef) / L).toInt(); val kMax = ceil((17f - zRef) / L).toInt()
        val keep = colorShader.globalFade
        // the banded fibrils: a static smooth mesh whose bands the shader lays out from L each frame
        Matrix.setIdentityM(model, 0); Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        t2BandMesh(i).draw(sphere, mvp, L, zRef, colorShader.globalFade)
        for (k in kMin..kMax) {
            val zz = zRef + k * L
            if (zz in -9f..17f) { t2Model(t2Frame(i, zz), 0f, 0f); t2Draw(m[1]); t2Draw(m[17], true); t2Draw(m[16], true); t2Draw(m[3], true) }
            val zm = zz + L * 0.5f
            if (zm in -9f..17f) {
                t2Model(t2Frame(i, zm), 0f, 0f); t2Draw(m[0])
                // cross-bridges: while the fibre shortens the heads row, each group out of step;
                // at rest they stand perpendicular to the filament
                // (the heads are shown while they work; at rest the ordered filament lattice shows)
                if (zm < 8f && rowing) for (grp in 0 until 3) {
                    val pose = ((((seconds + grp * 0.133f) / 0.4f) % 1f) * 4f).toInt().coerceIn(0, 3)
                    t2Draw(m[4 + grp * 4 + pose])
                }
                colorShader.globalFade = keep * (0.25f + 0.75f * flash); t2Draw(m[2], true); colorShader.globalFade = keep
            }
        }
        // calcium released from the terminal cisternae, spreading into the filaments
        if (flash > 0.02f) {
            t2LinesBegin()
            for (k in kMin..kMax) {
                val zm = zRef + k * L + L * 0.5f
                if (zm < -3f || zm > 12f) continue
                for (f in 0 until 6) {
                    val fa = f * T2PI / 3f; val cx = cos(fa) * 2.7f; val cy = sin(fa) * 2.7f
                    for (sgn in SIGNS) for (q in 0 until 5) {
                        val a = q * 1.26f + f; val r = 1.0f - 0.45f * (1f - flash) ; val dz = sgn * (1f + (q - 2) * 0.08f * (1.5f - flash))
                        val w = t2W(t2Frame(i, zm + dz), cx + cos(a) * r, cy + sin(a) * r)
                        t2Vert(w[0], w[1], w[2], T2_CALCIUM, flash)
                    }
                }
            }
            t2LinesEnd(1f, true, 4f)
        }
    }
}

// =========================================================================== stop 6: MARROW
// 8 µm a unit (Mote 12 µm). Red marrow inside the femur: cream trabeculae 80-100 µm thick in the
// distance lined by osteoblasts, an osteoclast in its pit; between them haematopoietic cords of
// mixed precursors, fat cells, a sinusoid with blood flowing; a megakaryocyte (~50 µm, lobed
// polyploid nucleus) pushing beaded proplatelets through the sinusoid wall; an erythroblastic
// island (a macrophage ringed by maturing erythroblasts, one extruding its nucleus); a young B
// cell against a stromal cell by the vessel wall; a stem cell dividing. The same scene serves
// Chapter III's marrow stop. Everything is laid out in the node's rigid frame and kept clear of
// the actual (curving) rail.

private val T2_MK_C = floatArrayOf(5.2f, 0.6f, 8.6f)
private val T2_MK_R = floatArrayOf(3.1f, 2.8f, 3.0f)
private val T2_SIN = floatArrayOf(3.4f, -4.9f, 2.8f)        // sinusoid axis (x, y) and radius, along z
private val T2_ISLAND = floatArrayOf(-4.2f, 1.8f, 9.0f)
private val T2_HSC = floatArrayOf(-3.2f, 2.8f, 5.5f)
private val T2_BCELL = floatArrayOf(1.4f, -2.35f, 7.5f)

/** The rail's height (rigid-frame y) where it crosses rigid-frame [z] past stop [i]. */
private fun StereoBodyRenderer.t2RailYAtRigidZ(i: Int, z: Float): Float {
    val rail = t2Rail(i); val f0 = FloatArray(13); rail.at(0f, f0); val fr = FloatArray(13)
    var a = 0f
    while (a < 60f) {
        rail.at(a, fr)
        val rz = (fr[0] - f0[0]) * f0[3] + (fr[1] - f0[1]) * f0[4] + (fr[2] - f0[2]) * f0[5]
        if (rz >= z) return (fr[0] - f0[0]) * f0[9] + (fr[1] - f0[1]) * f0[10] + (fr[2] - f0[2]) * f0[11]
        a += 0.05f
    }
    return 0f
}

/** The rail centre at arc position z, in the node's rigid frame (x, y). */
private fun StereoBodyRenderer.t2RailOffset(i: Int, z: Float, out: FloatArray) {
    val rail = t2Rail(i); val f0 = t2G; rail.at(0f, f0); val fr = t2F; rail.at(z, fr)
    val rx = fr[0] - f0[0]; val ry = fr[1] - f0[1]; val rz = fr[2] - f0[2]
    out[0] = rx * f0[6] + ry * f0[7] + rz * f0[8]; out[1] = rx * f0[9] + ry * f0[10] + rz * f0[11]
}

/** Distance from p to the segment a-b. */
private fun t2SegDist(p: T2V, a: T2V, b: T2V): Float {
    val ab = b - a; val t = (((p - a) dot ab) / max(1e-6f, ab dot ab)).coerceIn(0f, 1f); return (p - (a + ab * t)).len()
}

/** A red cell (biconcave, radius [r]) with its disc normal along [n]. */
private fun T2Geo.redCell(c: T2V, n: T2V, r: Float, col: FloatArray) {
    val nn = n.unit(); val e1 = t2perp(nn); val e2 = nn cross e1
    surf(8, 14, col) { u, v ->
        val top = u < 0.5f; val rr = (if (top) 1f - u * 2f else (u - 0.5f) * 2f).coerceIn(0f, 0.999f); val q = rr * rr
        val h = 0.5f * sqrt(1f - q) * (0.81f + 7.83f * q - 4.39f * q * q) / 3.91f
        val a = v * 2f * T2PI
        c + (e1 * cos(a) + e2 * sin(a)) * (rr * r) + nn * ((if (top) h else -h) * r)
    }
}

private fun StereoBodyRenderer.t2MarrowMeshes(i: Int): Array<ColorVboMesh> = t2Get("marrow$i") {
    val g = T2Geo(); val glass = T2Geo(); val rnd = java.util.Random(61L)
    val off = FloatArray(2)
    // ---- red marrow closes the view on every side: a backdrop of haematopoietic tissue
    g.surf(14, 24, T2_MARROW_BG) { u, v -> val th = u * T2PI; val ph = v * 2f * T2PI; t2v(sin(th) * cos(ph) * 11f, sin(th) * sin(ph) * 10f, 6f + cos(th) * 12f) }
    // ---- trabeculae: thick bony struts; the upper one arches over the lane with its underside
    //      about 5 units (40 µm) away, lined by a row of cuboidal osteoblasts, and further back an
    //      osteoclast sits in a scooped resorption pit (Howship's lacuna)
    val beamPts = arrayOf(
        listOf(t2v(-20f, 11f, -2f), t2v(-8f, 10.3f, 3f), t2v(0f, 10.4f, 8f), t2v(8f, 11f, 13f), t2v(20f, 15f, 26f)),
        listOf(t2v(-14f, -22f, 27f), t2v(-13f, -2f, 29f), t2v(-12f, 18f, 31f)),
        listOf(t2v(17f, -19f, 9f), t2v(16f, -7f, 22f), t2v(15f, 4f, 35f)))
    val beamR = floatArrayOf(5.4f, 5f, 5f)
    for ((bi, bp) in beamPts.withIndex()) {
        val r = beamR[bi]
        g.path(bp, { t -> if (bi == 0) r else r * (0.93f + 0.1f * sin(t * 9f + bi)) }, T2_BONE, 1f, 16, true)
        if (bi > 0) for (q in 0 until 5) { val a = bp.first(); val c = bp.last(); g.ball(a + (c - a) * ((q + 0.5f) / 5f) + t2v(rnd.nextFloat() - 0.5f, 0.6f, rnd.nextFloat() - 0.5f) * (r * 0.8f), r * 0.5f, T2_BONE, 1f, 7, 10) }
    }
    fun onBeam(p: T2V, lift: Float): Pair<T2V, T2V> {   // surface point of the upper beam nearest p, and its outward normal
        val bp = beamPts[0]; var best = bp[0]; var bd = 1e9f
        for (q in 0 until bp.size - 1) { val a = bp[q]; val ab = bp[q + 1] - a; val t = (((p - a) dot ab) / (ab dot ab)).coerceIn(0f, 1f); val c = a + ab * t; val dd = (p - c).len(); if (dd < bd) { bd = dd; best = c } }
        val nrm = (p - best).unit(); return Pair(best + nrm * (beamR[0] + lift), nrm)
    }
    for (q in 0 until 12) {   // osteoblasts: cuboidal, on the bone, nucleus toward the marrow
        val (c, nrm) = onBeam(t2v(-1.5f + q * 0.5f, 3f, 6f + q * 0.52f), 0.45f)
        val e1 = t2perp(nrm); val e2 = nrm cross e1
        g.ellAxis(c, nrm, 0.62f, 0.5f, 0.62f, T2_OSTEOBLAST_2, 1f, 7, 10, e1)
        g.ball(c + nrm * 0.28f, 0.26f, T2_HEP_NUC, 1f, 5, 7)
    }
    run {   // osteoclast: large, multinucleate, its ruffled border against the bone in a scooped pit
        val (c0, nrm0) = onBeam(t2v(-7.5f, 2f, 2.5f), 0.55f)
        val c = c0
        g.ell(c - nrm0 * 0.5f, t2v(2.6f, 0f, 0f), t2v(0f, 0.14f, 0f), t2v(0f, 0f, 1.8f), T2_LACUNA)
        g.ell(c, t2v(2.25f, 0f, 0f), t2v(0f, 0.75f, 0f), t2v(0f, 0f, 1.5f), T2_OSTEOCLAST, 1f, 10, 14)
        for (k in 0 until 6) { val a = k * 1.05f; g.ball(c + t2v(cos(a) * 1.2f, -0.35f, sin(a) * 0.8f), 0.35f, T2_HEP_NUC, 1f, 5, 7) }
        for (k in 0 until 10) { val a = k * 0.63f; g.ball(c + t2v(cos(a) * 1.5f, 0.65f, sin(a) * 1.0f), 0.22f, T2_OSTEOCLAST, 1f, 4, 6) }
    }
    // ---- the sinusoid: a wide thin-walled vessel below the lane, endothelial nuclei on its wall
    val sx = T2_SIN[0]; val sy = T2_SIN[1]; val sr = T2_SIN[2]
    glass.surf(30, 26, T2_SINUSOID, 0.26f) { v, u -> val a = u * 2f * T2PI; t2v(sx + cos(a) * sr, sy + sin(a) * sr, -9f + 26f * v) }
    repeat(10) { val a = rnd.nextFloat() * 2f * T2PI; val z = -6f + rnd.nextFloat() * 20f
        glass.ellAxis(t2v(sx + cos(a) * sr, sy + sin(a) * sr, z), t2v(cos(a), sin(a), 0f), 0.35f, 0.14f, 0.8f, T2_NUCLEUS, 0.7f, 4, 7) }
    // ---- megakaryocyte: a lobed polyploid nucleus (six fused lobes) and a granular cytoplasm
    //      (drawn translucent per frame over it)
    val mk = t2v(T2_MK_C[0], T2_MK_C[1], T2_MK_C[2])
    for (k in 0 until 6) { val a = k * 1.05f; g.ball(mk + t2v(cos(a) * 0.9f, sin(a * 1.3f) * 0.6f, sin(a) * 0.9f), 1.1f, T2_MEGA_NUC, 1f, 7, 10) }
    repeat(40) {
        var q: T2V; do { q = t2v(rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f) } while (q.len() > 1f || q.len() < 0.7f)
        g.ball(mk + t2v(q.x * T2_MK_R[0], q.y * T2_MK_R[1], q.z * T2_MK_R[2]) * 0.92f, 0.07f, T2_MK_GRANULE, 1f, 3, 4)
    }
    // proplatelets: beaded cytoplasmic strands pushed through the sinusoid wall, trailing downstream
    for (s in 0 until 3) {
        val st = mk + t2v(-1.6f + s * 0.4f, -2.4f, -0.8f + s * 0.8f)
        val pts = ArrayList<T2V>()
        for (q in 0..10) { val t = q / 10f
            val p = if (t < 0.4f) st + (t2v(sx - 0.5f + s * 0.3f, sy + 1.3f, st.z) - st) * (t / 0.4f) else t2v(sx - 0.5f + s * 0.3f, sy + 1.3f - s * 0.6f, st.z + (t - 0.4f) * 7f)
            pts.add(p) }
        g.path(pts, { 0.06f }, T2_PLATELET, 1f, 5, true)
        for (q in 1..10) { g.ball(pts[q], 0.2f + 0.03f * (q % 2), T2_PLATELET, 1f, 5, 7); for (gr in 0 until 3) g.ball(pts[q] + t2v(cos(gr * 2.1f), sin(gr * 2.1f), 0.5f).unit() * 0.17f, 0.05f, T2_PLT_GRAN, 1f, 3, 4) }
    }
    // ---- erythroblastic island: central macrophage ringed by erythroblasts maturing round it
    val isl = t2v(T2_ISLAND[0], T2_ISLAND[1], T2_ISLAND[2])
    // the central macrophage: kidney-shaped nucleus and ingested erythroblast nuclei seen through its body
    glass.ball(isl, 1.0f, T2_MACROPHAGE, 0.7f, 9, 12)
    g.torus(isl, t2v(0.3f, 1f, 0.2f), 0.35f, 0.16f, T2_HEP_NUC, 1f, 12, 6, 0f, 1.4f * T2PI)
    for (k in 0 until 3) g.ball(isl + t2v(cos(k * 2.1f + 0.5f), 0.4f * sin(k * 1.7f), sin(k * 2.1f + 0.5f)) * 0.55f, 0.15f, T2_INGESTED, 1f, 5, 7)
    // the ring faces the lane (lies across the line of sight), macrophage processes between its cells
    val toLane = (t2v(0f, 0f, -2f) - isl).unit(); val ie1 = t2perp(toLane); val ie2 = (toLane cross ie1).unit()
    for (k in 0 until 8) {
        val a = k * T2PI / 4f; val d = (ie1 * cos(a) + ie2 * sin(a)).unit()
        val dp = (ie1 * cos(a + T2PI / 8f) + ie2 * sin(a + T2PI / 8f)).unit()
        // flat processes lying in the ring plane between the erythroblasts (broad face to the lane)
        val tg = (toLane cross dp).unit()
        g.ellAxis(isl + dp * 1.25f, tg, 0.2f, 0.4f, 0.06f, T2_MACROPHAGE, 1f, 5, 8, dp)
        val t = k / 8f; val c = t2mix(T2_ERY_EARLY, T2_ERY_LATE, t)
        glass.ball(isl + d * 1.75f, 0.65f, c, 0.7f, 7, 10)
        g.ball(isl + d * 1.75f, 0.34f - 0.18f * t, t2mix(T2_BLAST_NUC, T2_NORMO_NUC, t), 1f, 5, 7)
    }
    // ---- B-cell precursor held by a reticular stromal cell by the sinusoid wall
    val bc = t2v(T2_BCELL[0], T2_BCELL[1], T2_BCELL[2])
    g.ball(bc, 0.42f, T2_BCELL_NUC, 1f, 7, 10)
    glass.ball(bc, 0.5f, T2_BCELL_CYTO, 0.35f, 7, 10)
    // the stromal (reticular) cell holding it: a flat body with three broad processes wrapping
    // the B cell, the sinusoid wall and a neighbour
    val stc = bc + t2v(0.4f, -0.9f, 0.9f)
    g.ellAxis(stc, t2v(0.3f, 0.2f, 1f), 0.9f, 0.3f, 0.4f, T2_STROMA, 1f, 6, 9)
    for (tg in listOf(bc + t2v(0f, -0.35f, 0.15f), t2v(sx - sr * 0.6f, sy + sr * 0.8f, stc.z + 0.6f), stc + t2v(-1.2f, -0.6f, 1.4f))) {
        val mid = (stc + tg) * 0.5f; val dir = (tg - stc).unit()
        g.ellAxis(mid, dir, 0.6f, (tg - stc).len() * 0.55f, 0.18f, T2_STROMA, 1f, 6, 9)
    }
    // ---- adipocytes: big clear fat cells with the nucleus squeezed to the rim
    val fats = arrayOf(floatArrayOf(-7.8f, 5.2f, 12f, 3.6f), floatArrayOf(8.2f, 6.6f, 15f, 3.3f), floatArrayOf(-8.2f, -6.2f, 14f, 3.4f), floatArrayOf(2.5f, 7.6f, 1f, 2.6f))
    for (f in fats) { glass.ball(t2v(f[0], f[1], f[2]), f[3], T2_FAT, 0.3f, 10, 14); g.ellAxis(t2v(f[0], f[1], f[2]) + t2v(-f[0], -f[1], 0f).unit() * (f[3] - 0.15f), t2v(-f[0], -f[1], 0f), 0.7f, 0.2f, 0.5f, T2_NUCLEUS, 1f, 5, 8) }
    // ---- haematopoietic cords: precursors packed cell against cell in every space left, each
    //      told by its nucleus: blasts (big round nucleus), myelocytes (horseshoe nucleus, lilac
    //      granular cytoplasm), normoblasts (small dense nucleus, cytoplasm turning pink-red),
    //      lymphoid cells (nucleus nearly fills the cell); a few red cells among them.
    val placedP = ArrayList<T2V>(); val placedR = ArrayList<Float>()
    var tries = 0
    val target = 180
    while (placedP.size < target && tries < 60000) {
        tries++
        val x = (rnd.nextFloat() * 2f - 1f) * 10f; val y = (rnd.nextFloat() * 2f - 1f) * 9.5f; val z = -6f + rnd.nextFloat() * 22f
        val p = t2v(x, y, z)
        val kind = rnd.nextFloat()
        val r = when { kind < 0.1f -> 0.7f; kind < 0.62f -> 0.75f; kind < 0.85f -> 0.5f; kind < 0.93f -> 0.45f; else -> 0.47f } * (0.9f + 0.2f * rnd.nextFloat())
        t2RailOffset(i, z, off)
        val lx = x - off[0]; val ly = y - off[1]
        if (lx * lx + ly * ly < 2.9f * 2.9f) continue
        if ((x / 10.5f).pow(2) + (y / 9.5f).pow(2) + ((z - 6f) / 11.5f).pow(2) > 1f) continue
        val dsx = x - sx; val dsy = y - sy; if (sqrt(dsx * dsx + dsy * dsy) < sr + r + 0.1f) continue
        val mx = (x - T2_MK_C[0]) / (T2_MK_R[0] + r + 0.1f); val my = (y - T2_MK_C[1]) / (T2_MK_R[1] + r + 0.1f); val mz = (z - T2_MK_C[2]) / (T2_MK_R[2] + r + 0.1f)
        if (mx * mx + my * my + mz * mz < 1f) continue
        if ((p - isl).len() < 2.7f + r) continue
        if ((p - t2v(T2_HSC[0], T2_HSC[1], T2_HSC[2])).len() < 1.2f + r) continue
        if ((p - bc).len() < 1.4f + r || (p - stc).len() < 1f + r) continue
        if (fats.any { f -> (p - t2v(f[0], f[1], f[2])).len() < f[3] + r }) continue
        var bad = false
        for ((bi, bp) in beamPts.withIndex()) for (q in 0 until bp.size - 1) if (t2SegDist(p, bp[q], bp[q + 1]) < beamR[bi] + r) bad = true
        if (bad) continue
        for (q in placedP.indices) if ((placedP[q] - p).len() < placedR[q] + r - 0.05f) { bad = true; break }
        if (bad) continue
        placedP.add(p); placedR.add(r)
        when {
            kind < 0.1f -> { glass.ball(p, r, T2_BLAST_CYTO, 0.5f, 6, 9); g.ball(p, r * 0.68f, T2_BLAST_NUC, 1f, 6, 9) }
            kind < 0.62f -> {
                glass.ball(p, r, T2_MYELO_CYTO, 0.55f, 6, 9)
                val nrm = t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
                g.torus(p, nrm, r * 0.4f, r * 0.19f, T2_MYELO_NUC, 1f, 10, 6, 0f, 0.7f * 2f * T2PI)
                repeat(6) { val q = t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit(); g.ball(p + q * (r * 0.7f), 0.06f, T2_MYELO_GRAN, 1f, 3, 4) }
            }
            kind < 0.85f -> {
                val ripe = 0.3f + 0.7f * rnd.nextFloat()
                glass.ball(p, r, t2mix(T2_NORMO_EARLY, T2_NORMO_LATE, ripe), if (ripe < 0.5f) 0.45f else 0.6f, 6, 9)
                g.ball(p, r * 0.4f, T2_NORMO_NUC, 1f, 5, 7)
            }
            kind < 0.93f -> { glass.ball(p, r, T2_LYMPH_CYTO, 0.45f, 6, 9); g.ball(p, r * 0.74f, T2_LYMPH_NUC2, 1f, 6, 9) }
            else -> g.redCell(p, t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f), r, COL_RBC_DEOXY)
        }
    }
    val rigid = t2Bend(i, 0f, 0f)
    arrayOf(TriMesh(g.baked(rigid)), TriMesh(glass.baked(rigid)))
}

/** Tour II stop 7 (and Chapter III's marrow): the blood factory inside the bone. */
internal fun StereoBodyRenderer.drawMarrow(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val m = t2MarrowMeshes(i)
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        t2DrawWorld(m[0])
        // blood in the sinusoid: red cells and freshly shed platelets flowing downstream
        val sx = T2_SIN[0]; val sy = T2_SIN[1]
        for (k in 0 until (if (quality == 0) 9 else 4)) {
            val z = ((seconds * 0.9f + k * 2.9f) % 26f) - 9f
            val a = k * 2.3f; val r = 1.4f * ((k * 37) % 10) / 10f
            val p = t2W(f0, sx + cos(a) * r, sy + sin(a) * r, z).copyOf()
            val tb = seconds * 0.4f + k
            t2Basis(p, f0[3], f0[4], f0[5], f0[6] * cos(tb) + f0[9] * sin(tb), f0[7] * cos(tb) + f0[10] * sin(tb), f0[8] * cos(tb) + f0[11] * sin(tb), 0.47f, 0.47f, 0.47f, rbc, COL_RBC_DEOXY, COL_RBC_RIM)
        }
        for (s in 0 until 3) {   // a platelet breaking off each proplatelet tip every few seconds
            val t = ((seconds / 3.2f) + s * 0.33f) % 1f
            val z = T2_MK_C[2] - 0.8f + s * 0.8f + 6f + t * 9f
            val p = t2W(f0, sx - 0.5f + s * 0.3f, sy + 1.3f - s * 0.6f - t * 0.4f, z).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.2f, 0.08f, 0.16f, sphere, T2_PLATELET, COL_LAMP, 1f - t * 0.5f)
        }
        // erythroblastic island: every 12 s the ripest erythroblast extrudes its nucleus (the
        // macrophage eats it) and leaves as a reticulocyte for the sinusoid
        run {
            val t = (seconds % 12f) / 12f
            val isl = T2_ISLAND
            val iv = t2v(isl[0], isl[1], isl[2]); val toLane = (t2v(0f, 0f, -2f) - iv).unit(); val ie1 = t2perp(toLane); val ie2 = (toLane cross ie1).unit()
            val a = 7f * T2PI / 4f + T2PI / 8f; val dd = (ie1 * cos(a) + ie2 * sin(a)).unit()
            val ex = isl[0] + dd.x * 1.95f; val ey = isl[1] + dd.y * 1.95f; val ez = isl[2] + dd.z * 1.95f
            val out = t2sm((t - 0.2f) / 0.2f); val go = t2sm((t - 0.45f) / 0.45f)
            val nx = ex + (isl[0] - ex) * out * 0.5f; val ny = ey + (isl[1] - ey) * out * 0.5f + 0.3f * out; val nz = ez + (isl[2] - ez) * out * 0.5f
            val nr = 0.24f * (1f - t2sm((t - 0.4f) / 0.3f))
            if (nr > 0.02f) t2Basis(t2W(f0, nx, ny, nz).copyOf(), f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], nr, nr, nr, sphere, T2_NUCLEUS)
            val cx = ex + (sx - 0.8f - ex) * go; val cy = ey + (sy + 0.6f - ey) * go; val cz = ez + (ez + 3f - ez) * go
            val p = t2W(f0, cx, cy, cz).copyOf()
            val al = 1f - t2sm((t - 0.85f) / 0.15f)
            if (out < 0.5f) t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.44f, 0.44f, 0.44f, sphere, T2_NORMOBLAST, COL_LAMP, al)
            else t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.46f, 0.46f, 0.46f, rbc, T2_RETIC, COL_RBC_RIM, al)
        }
        // a haematopoietic stem cell dividing (16 s): chromosomes gather on a plate, part, and the
        // cell pinches in two; one daughter stays, the other drifts off to differentiate.
        run {
            val t = (seconds % 16f) / 16f
            val h = T2_HSC
            val split = t2sm((t - 0.45f) / 0.15f); val pinch = t2sm((t - 0.6f) / 0.15f); val away = t2sm((t - 0.78f) / 0.2f)
            val sep = 0.25f * split + 0.3f * pinch
            for (sgn in SIGNS) {
                val dzc = sgn * (sep + (if (sgn > 0f) away * 1.6f else 0f))
                val p = t2W(f0, h[0] + (if (sgn > 0f) away * 0.8f else 0f), h[1], h[2] + dzc).copyOf()
                val al = if (sgn > 0f) 1f - t2sm((t - 0.9f) / 0.1f) else 1f
                val plate = t2W(f0, h[0], h[1], h[2] + sgn * 0.3f * split + dzc * pinch).copyOf()
                if (t > 0.2f && t < 0.8f) t2Basis(plate, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.3f, 0.3f, 0.06f, sphere, T2_CHROMATID, COL_LAMP, t2sm((t - 0.2f) / 0.1f) * (1f - t2sm((t - 0.7f) / 0.1f)), 0.3f)
                if (pinch > 0.02f || sgn < 0f) {
                    val r = 0.5f - 0.1f * pinch
                    t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], r, r, r * (1f + 0.4f * split * (1f - pinch)), sphere, T2_LYMPHOID, COL_LAMP, (if (pinch < 0.02f) 0.55f else 0.6f) * al)
                    if (t < 0.2f || t > 0.8f) t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROERYTHRO, COL_LAMP, al)
                }
            }
        }
        t2DrawWorld(m[1], true)
        run {   // a faint halo marks the young B cell by the vessel wall, the one the next leg enters
            val p = t2W(f0, T2_BCELL[0], T2_BCELL[1], T2_BCELL[2]).copyOf()
            val pulse = 0.5f + 0.5f * sin(seconds * 2f)
            t2Basis(p, camNowX - p[0], camNowY - p[1], camNowZ - p[2], f0[9], f0[10], f0[11], 0.62f, 0.62f, 1f, t2RingMesh(), T2_BCELL_CYTO, T2_BCELL_CYTO, 0.25f + 0.2f * pulse, 0.5f)
        }
        // megakaryocyte cytoplasm, translucent over its lobed nucleus
        val mkp = t2W(f0, T2_MK_C[0], T2_MK_C[1], T2_MK_C[2]).copyOf()
        t2Basis(mkp, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], T2_MK_R[0], T2_MK_R[1], T2_MK_R[2], sphere, T2_MEGA, T2_MEGA, 0.6f, 0.05f)
    }
}

// ============================================================================== stop 7: V(D)J
// 8 nm a unit (Mote 12 nm). The heavy-chain locus of a pro-B cell as a real double helix (2 nm
// wide, 3.4 nm pitch) running beside the craft, nucleosome-packed on either side, open (no
// nucleosomes) across the recombining region; gene segments are colour-coded stretches of helix
// with their recombination signal sequences (white). In two steps per round — D to J, then V to
// DJ — the RAG1/RAG2 complex pairs a 12-RSS with a 23-RSS, the DNA between loops out, both are
// cut, the loop leaves as a closed signal-joint circle, Ku rings grab the coding ends, TdT adds
// a few random N nucleotides and ligase IV seals the joint. The segment lengths and the gaps
// between them are compressed ~10x along the thread so a whole round fits the window.

private const val T2K_SP = 0
private const val T2K_V = 1
private const val T2K_D = 2
private const val T2K_J = 3
private const val T2K_C = 4
private const val T2K_R12 = 5
private const val T2K_R23 = 6
private const val T2K_N = 7

private class T2Piece(val len: Float, val kind: Int, val id: Int)

private fun t2Germline(): List<T2Piece> {
    val l = ArrayList<T2Piece>()
    l.add(T2Piece(0.5f, T2K_SP, 0))
    for (v in 3 downTo 1) { l.add(T2Piece(3.0f, T2K_V, v)); l.add(T2Piece(0.3f, T2K_R23, v)); l.add(T2Piece(if (v > 1) 0.4f else 0.9f, T2K_SP, 0)) }
    for (d in 1..3) { l.add(T2Piece(0.2f, T2K_R12, 10 + d)); l.add(T2Piece(0.6f, T2K_D, d)); l.add(T2Piece(0.2f, T2K_R12, 20 + d)); l.add(T2Piece(if (d < 3) 0.3f else 0.9f, T2K_SP, 0)) }
    for (j in 1..4) { l.add(T2Piece(0.3f, T2K_R23, 30 + j)); l.add(T2Piece(1.2f, T2K_J, j)); l.add(T2Piece(if (j < 4) 0.3f else 1.0f, T2K_SP, 0)) }
    l.add(T2Piece(2.0f, T2K_C, 0)); l.add(T2Piece(0.5f, T2K_SP, 0))
    return l
}

private fun t2PieceCol(p: T2Piece): FloatArray = when (p.kind) {
    T2K_V -> T2_SEG_V; T2K_D -> T2_SEG_D; T2K_J -> T2_SEG_J; T2K_C -> T2_SEG_C
    T2K_R12, T2K_R23 -> T2_RSS; T2K_N -> T2_SIGNAL; else -> T2_SEG_SPACER
}

/**
 * Two turns of B-DNA, length 1 along z, radius 0.125: the two sugar-phosphate backbones (radius
 * 0.05) in the segment's colour, ~130 degrees apart so a major and a minor groove show, and thin
 * neutral base pairs between them; a short rung-free grey gap at an end marks a segment boundary.
 */
private fun StereoBodyRenderer.t2HelixChunk(col: FloatArray, startGap: Boolean, endGap: Boolean, len: Float): TriMesh = t2Get("chunk${col.contentHashCode()}.$startGap.$endGap.$len") {
    // the helix is built at its real length, so the pitch (0.6 units, drawn a little long to read)
    // never gets squashed on short pieces; strands 2.2 rad apart (major and minor grooves)
    val g = T2Geo(); val rb = 0.14f; val gp = 0.05f; val pitch = 0.6f
    val z0 = -len * 0.5f
    val t0 = if (startGap) gp else 0f; val t1 = if (endGap) len - gp else len
    val col2 = floatArrayOf(col[0] * 0.7f, col[1] * 0.7f, col[2] * 0.7f, 1f)
    for (strand in 0..1) {
        val off = if (strand == 0) 0f else 2.2f
        fun pt(t: Float): T2V { val a = t / pitch * 2f * T2PI + off; return t2v(cos(a) * rb, sin(a) * rb, z0 + t) }
        val nq = max(6, (len * 40f).toInt())
        g.path((0..nq).map { q -> pt(t0 + (t1 - t0) * q / nq) }, { 0.035f }, if (strand == 0) col else col2, 1f, 6, false)
        if (startGap) g.path((0..3).map { q -> pt(gp * q / 3f) }, { 0.03f }, T2_DNA_GAP, 1f, 5, false)
        if (endGap) g.path((0..3).map { q -> pt(len - gp + gp * q / 3f) }, { 0.03f }, T2_DNA_GAP, 1f, 5, false)
    }
    var t = t0 + 0.03f
    while (t < t1) {   // base pairs, one every 0.06 (10 per turn)
        val a = t / pitch * 2f * T2PI
        g.tube(t2v(cos(a) * rb, sin(a) * rb, z0 + t), t2v(cos(a + 2.2f) * rb, sin(a + 2.2f) * rb, z0 + t), 0.028f, 0.028f, T2_BASEPAIR, 1f, 4, false)
        t += 0.06f
    }
    TriMesh(g.bakedBright())
}

private fun StereoBodyRenderer.t2RagMesh(): TriMesh = t2Get("rag2") {
    val g = T2Geo()
    // RAG1 (gold): two elongated lobes forming a V 60 degrees open from a stem; RAG2 (pale blue):
    // a flattened crescent on the outer side of each arm tip. Each arm tip has a groove the DNA
    // runs through, so the two RSSs are held side by side. Local z along the DNA; +y from the
    // stem toward it; arm tips at z = +-0.8, y = 1.35.
    // (the two RAG1 lobes part at a notch at the V's apex, over the stem)
    g.ball(t2v(0f, -0.3f, 0f), 0.36f, T2_RAG1, 1f, 7, 10)
    for (sd in SIGNS) {
        val dir = t2v(0f, cos(0.52f), sd * sin(0.52f))
        g.ellAxis(dir * 0.97f + t2v(0f, 0f, sd * 0.1f), dir, 0.38f, 0.8f, 0.38f, T2_RAG1, 1f, 8, 12)
        val tip = dir * 1.75f + t2v(0f, 0f, sd * 0.1f)
        g.torus(tip + t2v(0f, 0.1f, 0f), t2v(0f, 0f, 1f), 0.45f, 0.14f, T2_RAG2, 1f, 12, 6, 0.3f, 2.8f, t2v(1f, 0f, 0f))
    }
    TriMesh(g.bakedBright())
}

/** Nucleosome-packed chromatin on both sides of the working region (local coordinates, bent with the rail). */
private fun StereoBodyRenderer.t2Chromatin(i: Int): TriMesh = t2Get("chromatin$i") {
    val g = T2Geo(); val bx = 1.8f; val by = -0.4f
    for (sd in SIGNS) {
        val start = if (sd < 0f) -9.6f else 18.4f
        var prev: T2V? = null
        for (k in 0 until 7) {
            val z = start + sd * (0.9f + k * 2.3f)
            val zig = if (k % 2 == 0) 0.55f else -0.55f
            val c = t2v(bx + zig, by + zig * 0.6f, z)
            val ax = t2v(-c.x, -c.y, 0.35f * sd).unit()      // the disc faces the lane, its wrap seen face-on
            // histone octamer (11 nm x 6 nm) with 1.65 left-handed turns of DNA wrapped round it
            g.ellAxis(c, ax, 0.66f, 0.34f, 0.66f, T2_HISTONE, 1f, 7, 12)
            val e1 = t2perp(ax); val e2 = ax cross e1
            val wrap = (0..30).map { q -> val t = q / 30f; val a = t * 1.65f * 2f * T2PI; c + (e1 * cos(a) + e2 * sin(a)) * 0.9f + ax * ((t - 0.5f) * 0.5f) }
            g.path(wrap, { 0.15f }, T2_DNA_WRAP, 1f, 6, false)
            if (prev != null) g.path(listOf(prev, (prev + wrap[0]) * 0.5f + t2v(0f, 0.3f, 0f), wrap[0]), { 0.12f }, T2_DNA_WRAP, 1f, 6, false)
            prev = wrap.last()
        }
    }
    t2Bake(g, i, 3f, 9f)
}

/** Scratch for the locus layout: per piece, its start and end in local coords (x, y, z). */
private val t2Lay = FloatArray(64 * 6)

/** Tour II stop 8: V(D)J recombination on a double helix, in two steps. */
internal fun StereoBodyRenderer.drawVdj(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { _ ->
        t2DrawWorld(t2Chromatin(i))
        val round = (seconds / 36f).toInt(); val t = seconds % 36f
        val vPick = 1 + (round * 2) % 3; val dPick = 1 + (round + 1) % 3; val jPick = 1 + (round * 3 + 1) % 4
        val germ = t2Germline()
        // step 1: D-J (t 1..15); step 2: V-DJ (t 16..30); glow 30..33; reset 33..36
        val step = if (t < 15.5f) 0 else 1
        val tau = if (step == 0) t - 1f else t - 16f
        var list = germ
        if (step == 1) {   // DJ already joined: drop D(3') RSS .. J(5') RSS, put N nucleotides at the joint
            val a = germ.indexOfFirst { it.kind == T2K_R12 && it.id == 20 + dPick }; val b = germ.indexOfFirst { it.kind == T2K_R23 && it.id == 30 + jPick }
            list = germ.subList(0, a) + listOf(T2Piece(0.2f, T2K_N, 0)) + germ.subList(b + 1, germ.size)
        }
        val la: Int; val lb: Int
        if (step == 0) { la = list.indexOfFirst { it.kind == T2K_R12 && it.id == 20 + dPick }; lb = list.indexOfFirst { it.kind == T2K_R23 && it.id == 30 + jPick } }
        else { la = list.indexOfFirst { it.kind == T2K_R23 && it.id == vPick }; lb = list.indexOfFirst { it.kind == T2K_R12 && it.id == 10 + dPick } }
        val close = t2sm((tau - 1f) / 3f); val cut = tau > 4.5f
        val gap = if (!cut) 0f else 1.9f * (1f - t2sm((tau - 7.5f) / 2.5f))
        var sLoop = 0f; for (k in la..lb) sLoop += list[k].len
        val z0 = -9f; var sBefore = 0f; for (k in 0 until la) sBefore += list[k].len
        val zSyn = z0 + sBefore + sLoop * 0.5f
        val chord = if (!cut) sLoop + (1.9f - sLoop) * close else gap
        val bx = 1.8f; val by = -0.4f; val bdx = 0.35f; val bdy = 0.94f
        // arc through the loop: half-angle th with sin(th)/th = chord / sLoop
        var th = 0.001f
        if (!cut && chord < sLoop * 0.999f) { var lo = 0.001f; var hi = T2PI; repeat(30) { val mid = (lo + hi) * 0.5f; if (sin(mid) / mid > chord / sLoop) lo = mid else hi = mid }; th = (lo + hi) * 0.5f }
        val R = if (th > 0.002f) sLoop / (2f * th) else 1e6f
        val globalAlpha = if (t > 33f) 1f - t2sm((t - 33f) / 1.5f) else if (t < 1f) t2sm(t / 1f) else 1f
        val glow = if (t in 30f..33f) 0.6f * sin((t - 30f) / 3f * T2PI) else 0f
        // lay out every piece: left of the loop, the loop, right of the loop
        fun posOf(s: Float, out: FloatArray) {   // s = arc length along the list
            val sA = sBefore; val sB = sBefore + sLoop
            if (s <= sA) { val z = zSyn - chord * 0.5f - (sA - s); out[0] = bx; out[1] = by; out[2] = z }
            else if (s >= sB) { val z = zSyn + chord * 0.5f + (s - sB); out[0] = bx; out[1] = by; out[2] = z }
            else if (cut) { out[0] = bx; out[1] = by; out[2] = zSyn }
            else if (th < 0.002f) { out[0] = bx; out[1] = by; out[2] = zSyn - sLoop * 0.5f + (s - sA) }
            else { val phi = (s - sA) / R - th; val cth = cos(th)
                val bo = R * (cos(phi) - cth); out[0] = bx + bdx * bo; out[1] = by + bdy * bo; out[2] = zSyn + R * sin(phi) }
        }
        val pA = FloatArray(3); val pB = FloatArray(3)
        var s = 0f
        for ((k, pc) in list.withIndex()) {
            if (cut && k in la..lb) { s += pc.len; continue }
            val nch = max(1, ceil(pc.len / 0.85f).toInt())
            for (c in 0 until nch) {
                posOf(s + pc.len * c / nch, pA); posOf(s + pc.len * (c + 1) / nch, pB)
                t2DrawChunk(i, pA, pB, t2PieceCol(pc), c == 0, c == nch - 1, globalAlpha)
            }
            s += pc.len
        }
        // the excised loop, closed into a signal-joint circle, drifting off and fading
        if (cut && tau < 9.5f) {
            val drift = t2sm((tau - 4.5f) / 5f) * 3f; val al = globalAlpha * (1f - t2sm((tau - 7.5f) / 2f))
            val rc = sLoop / (2f * T2PI)
            val cx = bx + bdx * (rc + drift); val cy = by + bdy * (rc + drift)
            var ss = 0f
            for (k in la..lb) {
                val pc = list[k]; val nch = max(1, ceil(pc.len / 0.85f).toInt())
                for (c in 0 until nch) {
                    val a0 = (ss + pc.len * c / nch) / rc; val a1 = (ss + pc.len * (c + 1) / nch) / rc
                    pA[0] = cx - bdx * rc * cos(a0); pA[1] = cy - bdy * rc * cos(a0); pA[2] = zSyn + rc * sin(a0)
                    pB[0] = cx - bdx * rc * cos(a1); pB[1] = cy - bdy * rc * cos(a1); pB[2] = zSyn + rc * sin(a1)
                    t2DrawChunk(i, pA, pB, t2PieceCol(pc), c == 0, c == nch - 1, al)
                }
                ss += pc.len
            }
        }
        // RAG1/2: scans in from the J region, then holds the two RSSs together in the synaptic complex
        val ragZ = if (step == 0) (zSyn + 6f + (-6f) * t2sm(tau / 1.5f)) else zSyn
        val ragOn = globalAlpha * (1f - t2sm((tau - 5.5f) / 1.5f))
        if (ragOn > 0.02f && tau > -0.5f) {
            val fr = t2Frame(i, ragZ)
            // the stem on the side away from the loop; the arms reach to the DNA, each holding an RSS
            val p = t2W(fr, bx - bdx * 1.62f, by - bdy * 1.62f)
            val yx = fr[6] * bdx + fr[9] * bdy; val yy = fr[7] * bdx + fr[10] * bdy; val yz = fr[8] * bdx + fr[11] * bdy
            val dc = sqrt((p[0] - camNowX).pow(2) + (p[1] - camNowY).pow(2) + (p[2] - camNowZ).pow(2))
            val rs = if (dc < 4f) 0.7f else 1f
            t2ModelBasis(p[0], p[1], p[2], fr[3], fr[4], fr[5], yx, yy, yz, rs, rs, rs)
            val keep = colorShader.globalFade
            colorShader.globalFade = keep * ragOn * (if (dc < 3f) 0.85f else 1f) * (if (tau in 4.3f..5.2f) 1f else 1f)
            t2Draw(t2RagMesh(), colorShader.globalFade < 0.99f)
            colorShader.globalFade = keep
        }
        // the cut: a flash at both RSS / coding borders
        if (tau in 4.2f..5.6f) {
            val fl = sin((tau - 4.2f) / 1.4f * T2PI)
            for (sd in SIGNS) { val fr = t2Frame(i, zSyn + sd * 0.95f); t2Blob(fr, bx, by, 0.25f, 0.25f, 0.25f, T2_RSS, T2_RSS, 0.9f * fl * globalAlpha, 1.5f) }
        }
        // repair: Ku70/80 rings on the two coding ends, TdT's random nucleotides, ligase IV sealing
        if (cut && tau < 11f) {
            val ku = t2sm((tau - 5f) / 1f) * (1f - t2sm((tau - 9.8f) / 1f)) * globalAlpha
            if (ku > 0.02f) for (sd in SIGNS) {
                val fr = t2Frame(i, zSyn + sd * (gap * 0.5f + 0.18f))
                t2Basis(t2W(fr, bx, by).copyOf(), fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], 1f, 1f, 1f, t2KuMesh(), T2_KU, COL_LAMP, ku, 0.1f)
            }
            val nn = ((tau - 6.5f) / 0.6f).toInt().coerceIn(0, 3)
            val ncol = arrayOf(T2_SEG_D, T2_SIGNAL, T2_SEG_V, T2_SEG_J)
            for (q in 0 until nn) { val fr = t2Frame(i, zSyn + (q - 1f) * 0.1f); t2Blob(fr, bx, by + 0.02f, 0.07f, 0.07f, 0.07f, ncol[(q + round) % 4], COL_LAMP, globalAlpha, 0.5f) }
            if (tau in 6.3f..8.6f) { val fr = t2Frame(i, zSyn); t2Blob(fr, bx + 0.35f, by + 0.45f, 0.36f, 0.3f, 0.3f, T2_TONSIL, COL_LAMP, globalAlpha * 0.95f) }     // TdT
            if (tau in 8.3f..10.8f) { val fr = t2Frame(i, zSyn); t2Blob(fr, bx - 0.4f, by + 0.5f, 0.5f, 0.4f, 0.4f, T2_LIGASE, COL_LAMP, globalAlpha * 0.95f, if (tau > 9.7f) 0.6f else 0.05f) }
        }
    }
}

/** A thin unit ring in the x/y plane (contractile ring, fusion pore). */
private fun StereoBodyRenderer.t2RingMesh(): LitMesh = t2Get("ring") {
    val g = T2Geo(); g.torus(t2v(0f, 0f, 0f), t2v(0f, 0f, 1f), 1f, 0.045f, T2_ACTOMYOSIN, 1f, 48, 8); T2Lit(g.lit())
}

private fun StereoBodyRenderer.t2KuMesh(): LitMesh = t2Get("ku") {
    val g = T2Geo(); g.torus(t2v(0f, 0f, 0f), t2v(0f, 0f, 1f), 0.34f, 0.16f, T2_KU, 1f, 16, 7); T2Lit(g.lit())
}

/** One helix chunk from local point a to b (in the stop's rail coordinates). */
private fun StereoBodyRenderer.t2DrawChunk(i: Int, a: FloatArray, b: FloatArray, col: FloatArray, sg: Boolean, eg: Boolean, alpha: Float) {
    if (alpha < 0.02f) return
    val fa = t2Frame(i, a[2]); val ax = fa[0] + fa[6] * a[0] + fa[9] * a[1]; val ay = fa[1] + fa[7] * a[0] + fa[10] * a[1]; val az = fa[2] + fa[8] * a[0] + fa[11] * a[1]
    val ux = fa[9]; val uy = fa[10]; val uz = fa[11]
    val fb = t2Frame(i, b[2]); val bxw = fb[0] + fb[6] * b[0] + fb[9] * b[1]; val byw = fb[1] + fb[7] * b[0] + fb[10] * b[1]; val bzw = fb[2] + fb[8] * b[0] + fb[11] * b[1]
    val dx = bxw - ax; val dy = byw - ay; val dz = bzw - az; val l = sqrt(dx * dx + dy * dy + dz * dz)
    if (l < 1e-4f) return
    val lq = max(0.05f, (l * 20f).roundToInt() / 20f)
    val chunk = t2HelixChunk(col, sg, eg, lq)
    t2ModelBasis((ax + bxw) * 0.5f, (ay + byw) * 0.5f, (az + bzw) * 0.5f, dx / l, dy / l, dz / l, ux, uy, uz, 1f, 1f, l / lq)
    val keep = colorShader.globalFade
    colorShader.globalFade = keep * alpha; t2Draw(chunk, alpha < 0.99f); colorShader.globalFade = keep
}

/** model = the basis drawBasis would build (local z along z, y toward y), scaled, at (x, y, z). */
private fun StereoBodyRenderer.t2ModelBasis(x: Float, y: Float, z: Float, zx: Float, zy: Float, zz: Float, yx0: Float, yy0: Float, yz0: Float, sx: Float, sy: Float, sz: Float) {
    var l = sqrt(zx * zx + zy * zy + zz * zz).coerceAtLeast(1e-6f)
    val Zx = zx / l; val Zy = zy / l; val Zz = zz / l
    val d = yx0 * Zx + yy0 * Zy + yz0 * Zz
    var Yx = yx0 - d * Zx; var Yy = yy0 - d * Zy; var Yz = yz0 - d * Zz
    l = sqrt(Yx * Yx + Yy * Yy + Yz * Yz)
    if (l < 1e-5f) { val p = t2perp(t2v(Zx, Zy, Zz)); Yx = p.x; Yy = p.y; Yz = p.z; l = 1f }
    Yx /= l; Yy /= l; Yz /= l
    val Xx = Yy * Zz - Yz * Zy; val Xy = Yz * Zx - Yx * Zz; val Xz = Yx * Zy - Yy * Zx
    model[0] = Xx * sx; model[1] = Xy * sx; model[2] = Xz * sx; model[3] = 0f
    model[4] = Yx * sy; model[5] = Yy * sy; model[6] = Yz * sy; model[7] = 0f
    model[8] = Zx * sz; model[9] = Zy * sz; model[10] = Zz * sz; model[11] = 0f
    model[12] = x; model[13] = y; model[14] = z; model[15] = 1f
}

// ========================================================================== stop 8: HIGHWAY
// 80 nm a unit (Mote 120 nm). A hepatocyte's cytoplasm: a microtubule (13 protofilaments, 25 nm)
// with its minus end capped by a γ-tubulin ring toward the cell centre (behind) and its plus
// end ahead (GTP cap, growing and shrinking). Kinesin walks hand over hand toward the plus end
// with a vesicle; a dynein dimer (ring-shaped AAA+ motor heads on stalks) hauls a lysosome
// toward the minus end. Around: rough ER sheets studded with 25 nm ribosomes to port, a
// mitochondrion with cristae to starboard, a Golgi stack ahead, lysosomes.

private const val T2_MT_X = -0.9f
private const val T2_MT_Y = -0.8f
private const val T2_MT_Z0 = -9f
private const val T2_MT_SEG = 1.5f
private const val T2_MT_NSEG = 12
private const val T2_MT_Z1 = T2_MT_Z0 + T2_MT_SEG * T2_MT_NSEG + 0.3f

/**
 * One 1.5-unit length of microtubule (local: axis along z, centred): 13 protofilaments of
 * alternating alpha/beta tubulin (4 nm each), staggered 0.92 nm per protofilament (the 3-start
 * helix); [cap] = the GTP-tubulin cap at the plus end instead.
 */
private fun StereoBodyRenderer.t2MtSegment(cap: Boolean): TriMesh = t2Get("mtseg$cap") {
    val g = T2Geo(); val len = if (cap) 0.3f else T2_MT_SEG
    for (k in 0 until 13) {
        val a = k * 2f * T2PI / 13f; val x = cos(a) * 0.125f; val y = sin(a) * 0.125f
        var z = -len * 0.5f + (k * 0.0092f) % 0.05f; var m = 0
        if (z > -len * 0.5f + 0.001f) g.tube(t2v(x, y, -len * 0.5f), t2v(x, y, z), 0.042f, 0.042f, T2_TUBULIN_B, 1f, 5, false)
        while (z < len * 0.5f - 0.001f) {
            val z1 = min(len * 0.5f, z + 0.05f)
            g.tube(t2v(x, y, z), t2v(x, y, z1), 0.042f, 0.04f, if (cap) T2_GTP_CAP else if (m % 2 == 0) T2_TUBULIN else T2_TUBULIN_B, 1f, 5, false)
            z = z1; m++
        }
    }
    TriMesh(g.baked())
}

/** The gamma-tubulin ring complex capping the minus end (local, at z = 0, facing -z). */
private fun StereoBodyRenderer.t2Gturc(): TriMesh = t2Get("gturc") {
    val g = T2Geo()
    g.torus(t2v(0f, 0f, -0.04f), t2v(0f, 0f, 1f), 0.13f, 0.05f, T2_GTURC, 1f, 13, 5)
    for (k in 0 until 13) { val a = k * 2f * T2PI / 13f; g.ball(t2v(cos(a) * 0.13f, sin(a) * 0.13f, -0.1f - 0.02f * k), 0.05f, T2_GTURC, 1f, 3, 5) }
    TriMesh(g.baked())
}

private fun StereoBodyRenderer.t2HighwayMeshes(i: Int): Array<ColorVboMesh> = t2Get("highway$i") {
    val g = T2Geo(); val glass = T2Geo(); val rnd = java.util.Random(71L); val lat = ArrayList<Float>()
    // more microtubules of the network, further off
    g.path(listOf(t2v(-6.5f, 6.5f, -6f), t2v(-4.5f, 5.8f, 8f), t2v(-2.5f, 5.0f, 22f)), { 0.155f }, T2_TUBULIN, 1f, 8, true)
    // mitochondrion (0.64 x 1.6 µm): outer and inner membranes translucent, lamellar cristae inside
    val mc = t2v(6.5f, -3.0f, 6f)
    fun capsuleSurf(geo: T2Geo, r: Float, half: Float, col: FloatArray, al: Float) = geo.surf(24, 20, col, al) { u, v ->
        val t = u * 2f - 1f; val a = v * 2f * T2PI
        val zz: Float; val rr: Float
        val cap = r / (half + r)
        if (t < -1f + cap) { val k = (t + 1f) / cap; val an = (1f - k) * T2PI / 2f; zz = -half - sin(an) * r; rr = cos(an) * r }
        else if (t > 1f - cap) { val k = (1f - t) / cap; val an = (1f - k) * T2PI / 2f; zz = half + sin(an) * r; rr = cos(an) * r }
        else { zz = t / (1f - cap) * half; rr = r }
        mc + t2v(cos(a) * rr, sin(a) * rr, zz)
    }
    capsuleSurf(glass, 4.0f, 6.0f, T2_MITO, 0.3f)
    capsuleSurf(glass, 3.72f, 5.75f, T2_MITO, 0.32f)
    for (k in 0 until 9) {
        val zc = mc.z - 7.2f + k * 1.8f; val off = if (k % 2 == 0) 0.55f else -0.55f
        val rmax = min(3.0f, sqrt(max(0.5f, 3.6f * 3.6f - max(0f, abs(zc - mc.z) - 5.4f).pow(2) * 4f)))
        g.ell(mc + t2v(off, (k % 3 - 1) * 0.4f, zc - mc.z), t2v(rmax, 0f, 0f), t2v(0f, rmax * 0.95f, 0f), t2v(0f, 0f, 0.16f), T2_MITO_CRISTA, 1f, 6, 14)
    }
    repeat(8) { g.ball(mc + t2v((rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 12f), 0.22f, T2_LYSO_CORE, 1f, 3, 5) }
    // rough ER: three flattened cisternae to port; ribosomes (25 nm, two subunits) crowd the
    // cytosolic face toward the road, many in polysome rows
    for (k in 0 until 3) {
        val xs = -5.4f - 1.8f * k
        for (face in 0..1) {
            val xo = if (face == 0) 0f else -0.6f
            val geo = if (k == 0) g else glass
            geo.surf(18, 24, if (face == 0) T2_ER else T2_HISTONE, if (geo === g) 1f else 0.45f) { u, v ->
                val yy = -6.5f + 13f * u; val zz = -7f + 21f * v
                t2v(xs + xo + 0.3f * sin(zz * 0.35f + k) + 0.15f * sin(yy * 0.6f), yy, zz)
            }
        }
    }
    // ribosomes docked on the ER in polysomes: curved rows of 5-8 on one mRNA (pink line)
    val poly = ArrayList<Float>()
    fun xs(yy: Float, zz: Float) = -5.4f + 0.3f * sin(zz * 0.35f) + 0.15f * sin(yy * 0.6f)
    repeat(34) {
        val cy = -5.4f + rnd.nextFloat() * 10.8f; val cz = -5.5f + rnd.nextFloat() * 18f
        val n = 5 + rnd.nextInt(4); val rr = 0.6f + rnd.nextFloat() * 0.3f; val a0 = rnd.nextFloat() * 2f * T2PI
        var prev: T2V? = null
        for (k in 0 until n) {
            val a = a0 + k * 0.62f; val yy = cy + cos(a) * rr * (1f + 0.15f * k); val zz = cz + sin(a) * rr * (1f + 0.15f * k)
            val x0 = xs(yy, zz)
            g.ball(t2v(x0 + 0.18f, yy, zz), 0.2f, T2_RIBO_60S, 1f, 4, 6)
            g.ball(t2v(x0 + 0.38f, yy + 0.04f, zz), 0.13f, T2_RIBO_40S, 1f, 4, 6)
            val m = t2v(x0 + 0.3f, yy, zz)
            prev?.let { poly.addAll(listOf(it.x, it.y, it.z, 0.98f, 0.42f, 0.72f, 0.9f, m.x, m.y, m.z, 0.98f, 0.42f, 0.72f, 0.9f)) }
            prev = m
        }
    }
    // Golgi stack ahead, cis face toward the ER: six curved cisternae with dilated rims, vesicles budding
    val ag = t2v(-1f, -0.25f, -0.2f).unit(); val ge1 = t2perp(ag); val ge2 = ag cross ge1
    val gc = t2v(4.6f, 4.6f, 12f)
    for (c in 0 until 6) {
        val off = (c - 2.5f) * 0.5f; val cc = gc + ag * off; val rc = 3.3f - 0.2f * abs(c - 2.5f)
        val col = t2mix(T2_GOLGI_TRANS, T2_GOLGI_CIS, c / 5f)
        g.surf(14, 24, col) { u, v ->
            val rho = if (u < 0.5f) u * 2f else (1f - u) * 2f
            val hs = (0.12f * sqrt(max(0f, 1f - rho * rho)) + 0.16f * exp(-((rho - 0.93f) / 0.07f).pow(2))) * (if (u < 0.5f) 1f else -1f)
            val a = v * 2f * T2PI
            cc + (ge1 * cos(a) + ge2 * sin(a)) * (rho * rc) + ag * (hs - 0.9f * rho * rho)
        }
    }
    repeat(14) { val a = rnd.nextFloat() * 2f * T2PI; val c = (rnd.nextFloat() - 0.5f) * 3f
        g.ball(gc + ag * (c - 0.8f) + (ge1 * cos(a) + ge2 * sin(a)) * (3.4f + rnd.nextFloat() * 0.5f), 0.32f + rnd.nextFloat() * 0.14f, T2_GOLGI_CIS, 1f, 5, 7) }
    // lysosomes: single membrane, dense heterogeneous contents
    for (l in arrayOf(floatArrayOf(-2.4f, 5.4f, 9f, 1.6f), floatArrayOf(-0.6f, -5.8f, 13f, 1.5f))) {
        val c = t2v(l[0], l[1], l[2]); glass.ball(c, l[3], T2_LYSOSOME, 0.4f, 10, 14)
        repeat(16) { g.ball(c + t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * (l[3] * 1.1f), 0.12f + rnd.nextFloat() * 0.16f, T2_LYSO_CORE, 1f, 3, 5) }
        g.torus(c, t2v(0.3f, 1f, 0.2f), l[3] * 0.45f, 0.06f, T2_LYSOSOME, 1f, 14, 4)
    }
    val bend = t2Bend(i, 3f, 9f)
    val lA = poly.toFloatArray(); for (k in 0 until lA.size / 7) bend(lA, k * 7)
    arrayOf(TriMesh(g.baked(bend)), TriMesh(glass.baked(bend)), LineMesh(lA))
}

/** Dynein's motor: a ring of six AAA+ domains (~13 nm), as one lit mesh in its x/y plane. */
private fun StereoBodyRenderer.t2DyneinRing(): LitMesh = t2Get("dyneinring") {
    val g = T2Geo(); for (k in 0 until 6) { val a = k * T2PI / 3f; g.ball(t2v(cos(a) * 0.08f, sin(a) * 0.08f, 0f), 0.045f, T2_DYNEIN, 1f, 5, 7) }
    T2Lit(g.lit())
}

/** Tour II stop 9: kinesin and dynein on a polarised microtubule through a hepatocyte's organelles. */
internal fun StereoBodyRenderer.drawHighway(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2HighwayMeshes(i)
        t2DrawWorld(m[0]); t2DrawWorld(m[2], true)
        val mx = T2_MT_X; val my = T2_MT_Y
        // the microtubule, drawn in the same rail frames as the motors that walk on it
        val seg = t2MtSegment(false)
        for (k in 0 until T2_MT_NSEG) { t2Model(t2Frame(i, T2_MT_Z0 + (k + 0.5f) * T2_MT_SEG), mx, my); t2Draw(seg) }
        t2Model(t2Frame(i, T2_MT_Z1 - 0.15f), mx, my); t2Draw(t2MtSegment(true))
        t2Model(t2Frame(i, T2_MT_Z0), mx, my); t2Draw(t2Gturc())
        // plus-end dynamic instability: growth with a GTP cap, then a catastrophe (protofilaments peel)
        val ph = (seconds % 14f) / 14f
        val ext = if (ph < 0.65f) 0.9f * t2sm(ph / 0.65f) else 0.9f * (1f - t2sm((ph - 0.65f) / 0.25f))
        if (ext > 0.03f) {
            val fr = t2Frame(i, T2_MT_Z1 + ext * 0.5f)
            t2Basis(t2W(fr, mx, my).copyOf(), fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], 0.155f, 0.155f, ext * 0.5f, cylinder, if (ph < 0.65f) T2_GTP_CAP else T2_TUBULIN, COL_LAMP, 1f, 0.2f)
        }
        t2LinesBegin()
        if (ph >= 0.65f && ph < 0.92f) {
            val zt = T2_MT_Z1 + ext
            for (k in 0 until 13) {
                val a = k * 2f * T2PI / 13f; var px = 0f; var py = 0f; var pz = 0f
                for (q in 0..5) {
                    val t = q / 5f; val curl = t * 1.6f
                    val r = 0.125f + 0.25f * sin(curl) * t; val zz = zt + 0.3f * sin(curl) * t
                    val w = t2W(t2Frame(i, zz), mx + cos(a) * r, my + sin(a) * r)
                    if (q > 0) t2Seg(px, py, pz, w[0], w[1], w[2], T2_TUBULIN, 0.9f)
                    px = w[0]; py = w[1]; pz = w[2]
                }
            }
        }
        // ---- kinesin: two motor heads (7-8 nm) seated on the top of the microtubule, stepping 16 nm
        //      each hand over hand (8 nm per step of the body), slowed ~50x; short neck linkers to a
        //      coiled-coil stalk 60 nm long; light chains at its tail holding the vesicle
        val stepLen = 0.1f; val walk = (seconds * 2f) % 1f; val stepNo = (seconds * 2f).toInt()
        val head = (stepNo * stepLen) % 9f + 0.5f
        val swing = t2sm(walk); val lift = sin(swing * T2PI) * 0.06f
        val footA = head; val footB = head - stepLen + 2f * stepLen * swing
        val body = (footA + footB) * 0.5f; val top = my + 0.21f
        val fade = t2sm((head - 0.5f) / 0.6f) * (1f - t2sm((head - 8.9f) / 0.6f))
        val fa = t2Frame(i, footA, FloatArray(13)); val a = t2W(fa, mx, top).copyOf()
        val fbb = t2Frame(i, footB, FloatArray(13)); val b = t2W(fbb, mx, top + lift).copyOf()
        val neck = t2W(t2Frame(i, body), mx, top + 0.11f).copyOf()
        val sway = 0.03f * sin(seconds * 2f * T2PI)
        val cz = body - 0.3f
        val tether = t2W(t2Frame(i, cz), mx - 0.3f + sway, top + 0.85f).copyOf()
        val cargo = t2W(t2Frame(i, cz - 0.3f), mx - 0.75f + sway, top + 1.45f).copyOf()
        if (fade > 0.02f) {
            t2Basis(a, fa[3], fa[4], fa[5], fa[9], fa[10], fa[11], 0.05f, 0.04f, 0.06f, sphere, COL_KINESIN, COL_KINESIN_LIGHT, fade, 0.3f)
            t2Basis(b, fbb[3], fbb[4], fbb[5], fbb[9], fbb[10], fbb[11], 0.05f, 0.04f, 0.06f, sphere, COL_KINESIN, COL_KINESIN_LIGHT, fade, 0.3f)
            drawStrut(a[0], a[1], a[2], neck[0], neck[1], neck[2], 0.012f, COL_KINESIN_LIGHT, COL_KINESIN_LIGHT, 0.3f)
            drawStrut(b[0], b[1], b[2], neck[0], neck[1], neck[2], 0.012f, COL_KINESIN_LIGHT, COL_KINESIN_LIGHT, 0.3f)
            drawStrut(neck[0], neck[1], neck[2], tether[0], tether[1], tether[2], 0.02f, COL_KINESIN, COL_KINESIN_LIGHT, 0.2f)
            t2Basis(tether, 0f, 0f, 1f, 0f, 1f, 0f, 0.06f, 0.06f, 0.06f, sphere, COL_KINESIN_LIGHT, COL_LAMP, fade, 0.3f)
            drawStrut(tether[0], tether[1], tether[2], cargo[0], cargo[1], cargo[2], 0.012f, COL_KINESIN_LIGHT, COL_KINESIN_LIGHT, 0.2f)
            for (k in 0 until 5) {
                val aa = k * 1.26f + seconds * 0.3f
                val p = t2W(t2Frame(i, cz - 0.3f + 0.3f * cos(aa)), mx - 0.75f + sway + 0.28f * sin(aa), top + 1.45f + 0.25f * sin(aa * 1.7f)).copyOf()
                t2Basis(p, 0f, 0f, 1f, 0f, 1f, 0f, 0.07f, 0.07f, 0.07f, sphere, COL_PROTEIN, COL_LAMP, fade)
            }
            t2Basis(cargo, 0f, 0f, 1f, 0f, 1f, 0f, 0.6f, 0.6f, 0.6f, sphere, COL_CARGO, COL_LAMP, 0.45f * fade, 0.2f)
        }
        // ---- cytoplasmic dynein on the lane side of the track, heading for the minus end: two AAA+
        //      ring motors (13 nm) on 15-nm stalks whose binding domains grip the microtubule; linkers
        //      and tails joined to dynactin (its 37-nm Arp1 filament, banded) and a coiled-coil
        //      adaptor holding a lysosome
        val dz = 9.5f - ((seconds * 0.15f + 0.5f) % 1f) * 9f
        val dfade = t2sm((9.5f - dz) / 0.6f) * t2sm((dz - 0.5f) / 0.6f)
        val ring = t2DyneinRing()
        val sx = mx + 0.2f
        val tail = t2W(t2Frame(i, dz + 0.1f), sx + 0.55f, my - 0.1f).copyOf()
        for (h in 0..1) {
            val hz = dz + h * 0.12f + 0.04f * sin(seconds * (2.6f + h * 0.7f) + h * 2f) + (if (((seconds * 1.3f + h).toInt() % 7) == 0) 0.05f else 0f)
            val fr = t2Frame(i, hz, FloatArray(13))
            val mtbd = t2W(fr, sx, my + (h - 0.5f) * 0.1f).copyOf()
            val rc = t2W(fr, sx + 0.27f, my + (h - 0.5f) * 0.25f).copyOf()
            t2Basis(mtbd, fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], 0.03f, 0.03f, 0.03f, sphere, T2_DYNEIN, T2_DYNEIN, dfade, 0.4f)
            drawStrut(rc[0], rc[1], rc[2], mtbd[0], mtbd[1], mtbd[2], 0.012f, T2_DYNEIN, T2_DYNEIN, 0.4f)
            t2Basis(rc, fr[6], fr[7], fr[8], fr[9], fr[10], fr[11], 1f, 1f, 1f, ring, T2_DYNEIN, T2_DYNEIN, dfade, 0.4f)
            drawStrut(rc[0], rc[1], rc[2], tail[0], tail[1], tail[2], 0.018f, T2_DYNEIN, T2_DYNEIN, 0.3f)
        }
        val fdt = t2Frame(i, dz + 0.1f, FloatArray(13))
        for (bnd in 0 until 7) {   // dynactin's Arp1 filament: 0.46 long, banded every 0.07
            val p = t2W(fdt, sx + 0.62f, my - 0.1f, -0.21f + bnd * 0.07f).copyOf()
            t2Basis(p, fdt[3], fdt[4], fdt[5], fdt[9], fdt[10], fdt[11], 0.06f, 0.06f, 0.032f, sphere, if (bnd % 2 == 0) T2_DYNACTIN else T2_ADAPTOR, T2_DYNACTIN, dfade, 0.3f)
        }
        val lyso = t2W(t2Frame(i, dz + 0.4f), sx + 1.3f, my - 0.9f).copyOf()
        val dyn = t2W(fdt, sx + 0.62f, my - 0.1f).copyOf()
        drawStrut(dyn[0], dyn[1], dyn[2], lyso[0], lyso[1], lyso[2], 0.02f, T2_ADAPTOR, T2_ADAPTOR, 0.3f)
        t2Basis(lyso, 0f, 0f, 1f, 0f, 1f, 0f, 0.6f, 0.6f, 0.6f, sphere, T2_LYSOSOME, T2_LYSO_CORE, 0.8f * dfade, 0.1f)
        t2LinesEnd(2f)
        t2DrawWorld(m[1], true)
    }
}

// ========================================================================== stop 9: FACTORY
// 8 nm a unit (Mote 12 nm). A pancreatic beta cell making insulin. Behind: the nucleus, the gene
// read by RNA polymerase II on its DNA; the mRNA runs through a nuclear pore complex (120 nm,
// eight-fold: rings, spokes, cytoplasmic filaments, nuclear basket) in the double nuclear
// envelope. Ahead: the rough ER (continuous with the outer nuclear membrane) with ribosomes
// docked on Sec61 translocons, the new chain threading into the lumen signal peptide first,
// cut, folded; far ahead, an insulin granule (~300 nm, crystalline core) fusing with the
// plasma membrane and releasing hexamers that fall apart into monomers.

/** The factory is laid out from the pore; this shifts it all 3 units ahead so the pore is framed from the stop. */
private const val T2_FZ = 12f
/** The ER stage (ribosomes on Sec61, the ER membrane, the COPII vesicle) sits this far to port, beside the pore rather than framed in its opening. */
private const val T2_SDX = -6.5f
private const val T2_SDY = 11f      // ...and up, above the pore's rim as seen from the stop
private fun t2s(x: Float, y: Float, z: Float) = T2V(x + T2_SDX, y + T2_SDY, z)

private fun StereoBodyRenderer.t2FactoryMeshes(i: Int): Array<ColorVboMesh> = t2Get("factory$i") {
    val g = T2Geo(); val glass = T2Geo(); val heads = ArrayList<Float>(); val fg = ArrayList<Float>(); val lam = ArrayList<Float>(); val rnd = java.util.Random(83L)
    // nuclear envelope: inner (z 0.2) and outer (z 4.4) membranes, 4.8 nm bilayers, joined round the pore
    for (zm in floatArrayOf(0.2f, 4.4f)) {
        glass.surf(8, 56, T2_ENVELOPE, 0.5f) { v, u -> val a = u * 2f * T2PI; val r = 8.35f + v * 20f; t2v(cos(a) * r, sin(a) * r, zm) }
        var r = 11.4f
        while (r < 26f) { val n = (2f * T2PI * r / 1.6f).toInt(); for (k in 0 until n) { val a = 2f * T2PI * k / n + r; for (s in SIGNS) heads.addAll(listOf(cos(a) * r, sin(a) * r, zm + s * 0.3f, 1f, 0.8f, 0.5f, 0.4f)) }; r += 1.6f }
    }
    glass.surf(10, 48, T2_ENVELOPE, 0.5f) { v, u ->
        val a = u * 2f * T2PI; val b = T2PI * 0.5f + v * T2PI; val rr = 8.35f + 2.1f * cos(b)
        t2v(cos(a) * rr, sin(a) * rr, 2.3f + 2.1f * sin(b))
    }
    // the pore complex: cytoplasmic, inner (spoke) and nuclear rings, eight-fold, each a continuous
    // ring carrying its eight subunits
    g.torus(t2v(0f, 0f, 4.8f), t2v(0f, 0f, 1f), 5.6f, 0.3f, T2_NPC_CYTO, 1f, 48, 8)
    g.torus(t2v(0f, 0f, -0.2f), t2v(0f, 0f, 1f), 5.6f, 0.3f, T2_NPC, 1f, 48, 8)
    g.torus(t2v(0f, 0f, 2.3f), t2v(0f, 0f, 1f), 4.1f, 0.5f, T2_NPC, 1f, 48, 8)
    for (k in 0 until 8) {
        val a = k * T2PI / 4f + T2PI / 8f; val d = t2v(cos(a), sin(a), 0f); val tg = t2v(-sin(a), cos(a), 0f)
        for (h in SIGNS) {   // the Y-complex pairs of the cytoplasmic and nuclear rings
            val a2 = a + h * 0.17f; val d2 = t2v(cos(a2), sin(a2), 0f); val t2 = t2v(-sin(a2), cos(a2), 0f)
            g.ell(d2 * 5.6f + t2v(0f, 0f, 4.95f), d2 * 0.62f, t2 * 0.45f, t2v(0f, 0f, 0.5f), T2_NPC_CYTO, 1f, 7, 10)
            g.ell(d2 * 5.6f + t2v(0f, 0f, -0.35f), d2 * 0.62f, t2 * 0.45f, t2v(0f, 0f, 0.5f), T2_NPC, 1f, 7, 10)
        }
        g.ell(d * 4.25f + t2v(0f, 0f, 2.3f), d * 0.7f, tg * 0.62f, t2v(0f, 0f, 1.3f), T2_NPC, 1f, 7, 10)
        g.tube(d * 4.4f + t2v(0f, 0f, 2.3f), d * 7.6f + t2v(0f, 0f, 2.3f), 0.28f, 0.28f, T2_NPC, 1f, 6, false)
        // cytoplasmic filaments reaching into the cytoplasm
        g.path((0..6).map { q -> val t = q / 6f; d * (5.4f + 0.8f * sin(t * 3f + k)) + tg * (0.6f * sin(t * 4f + k * 2f)) + t2v(0f, 0f, 5.4f + t * 5f) }, { t -> 0.12f - 0.05f * t }, T2_NPC, 1f, 5, true)
        // nuclear basket: filaments converging on the distal ring 75 nm into the nucleus
        g.path((0..6).map { q -> val t = q / 6f; val r = 5.3f + (2.6f - 5.3f) * t; d * r + t2v(0f, 0f, -0.6f - 5.6f * t - 0.6f * sin(t * T2PI)) }, { 0.1f }, T2_NPC, 1f, 5, true)
        // FG-repeat filaments filling the channel (the craft slips through them)
        repeat(8) {
            val z0 = 1.0f + rnd.nextFloat() * 2.6f; val a0 = a + (rnd.nextFloat() - 0.5f) * 0.7f
            var px = cos(a0) * 3.3f; var py = sin(a0) * 3.3f; var pz = z0
            for (q in 1..5) {
                val r = 3.3f - q * 0.36f; val aa = a0 + 0.25f * sin(q * 1.9f + it)
                val nx = cos(aa) * r; val ny = sin(aa) * r; val nz = z0 + 0.3f * sin(q * 1.3f + it * 0.7f)
                fg.addAll(listOf(px, py, pz, 0.85f, 0.85f, 1f, 0.35f, nx, ny, nz, 0.85f, 0.85f, 1f, 0.35f)); px = nx; py = ny; pz = nz
            }
        }
    }
    g.torus(t2v(0f, 0f, -6.2f), t2v(0f, 0f, 1f), 2.6f, 0.14f, T2_NPC, 1f, 24, 6)
    // the gene: the transcribed stretch open (a double helix crossing below, on the nuclear side;
    // right-handed once mapped into the scene), nucleosomes packing the DNA on either side
    val gx0 = -4.2f; val gx1 = 3.6f; val gnq = ((gx1 - gx0) * 12f).toInt()
    for (st in 0..1) g.path((0..gnq).map { q -> val x = gx0 + q * (gx1 - gx0) / gnq; val a = -x / 0.425f * 2f * T2PI + st * 2.3f; t2v(x, -2.1f + cos(a) * 0.1f, -1.6f + sin(a) * 0.1f) }, { 0.03f }, T2_DNA, 1f, 4, false)
    for (q in 0 until gnq * 2) { val x = gx0 + (q + 0.5f) * (gx1 - gx0) / (gnq * 2); val a = -x / 0.425f * 2f * T2PI
        g.tube(t2v(x, -2.1f + cos(a) * 0.1f, -1.6f + sin(a) * 0.1f), t2v(x, -2.1f + cos(a + 2.3f) * 0.1f, -1.6f + sin(a + 2.3f) * 0.1f), 0.016f, 0.016f, T2_DNA, 1f, 3, false) }
    for (sd in SIGNS) {
        val pts = ArrayList<T2V>(); pts.add(t2v(if (sd < 0f) gx0 else gx1, -2.1f, -1.6f))
        for (k in 0 until 3) {
            val cx = (if (sd < 0f) gx0 else gx1) + sd * (1.1f + k * 1.9f); val c = t2v(cx, -2.95f, -1.6f)
            g.ellAxis(c, t2v(1f, 0f, 0f), 0.7f, 0.35f, 0.7f, T2_HISTONE, 1f, 7, 12)
            // 1.65 turns wrapped round the octamer (a left-handed superhelix in the scene)
            for (q in 0..24) { val t = q / 24f; val a = T2PI * 0.5f + sd * t * 1.65f * 2f * T2PI; pts.add(c + t2v(sd * ((t - 0.5f) * 0.55f), cos(a) * 0.85f, sin(a) * 0.85f)) }
        }
        g.path(pts, { 0.12f }, T2_DNA, 1f, 6, false)
    }
    // the nuclear lamina: a meshwork of lamin filaments under the inner membrane
    run {
        var x = -20f
        while (x <= 20f) {
            var y = -20f
            while (y <= 20f) {
                val r0 = sqrt(x * x + y * y)
                if (r0 in 8f..20f) {
                    if (sqrt((x + 2.5f) * (x + 2.5f) + y * y) in 8f..20f) lam.addAll(listOf(x, y, -0.4f, 0.62f, 0.55f, 0.82f, 0.5f, x + 2.5f, y, -0.4f, 0.62f, 0.55f, 0.82f, 0.5f))
                    if (sqrt(x * x + (y + 2.5f) * (y + 2.5f)) in 8f..20f) lam.addAll(listOf(x, y, -0.4f, 0.62f, 0.55f, 0.82f, 0.5f, x, y + 2.5f, -0.4f, 0.62f, 0.55f, 0.82f, 0.5f))
                }
                y += 2.5f
            }
            x += 2.5f
        }
    }
    // messenger RNA: out of the polymerase, through the pore, onto the ribosomes (5' cap leading)
    //    (it leaves as an mRNP: export factors ride it through the central channel and drop off in
    //    the cytoplasm)
    val mr = listOf(t2v(1.2f, -1.4f, -0.6f), t2v(0.5f, -0.6f, 0.3f), t2v(0.05f, -0.1f, 1.0f), t2v(0f, 0f, 2.3f), t2v(0f, 0f, 4.8f), t2v(-0.9f + T2_SDX * 0.5f, -0.5f + T2_SDY * 0.5f, 5.4f),
        t2s(-1.8f, -0.9f, 6.0f), t2s(-1.8f, -0.9f, 7.8f), t2s(-1.8f, -0.9f, 9.6f), t2s(-1.7f, -0.8f, 11.4f))
    g.path(mr, { 0.07f }, T2_MRNA, 1f, 5, true)
    g.ball(mr.last() + t2v(0f, 0.05f, 0.1f), 0.14f, T2_MRNA, 1f, 4, 6)
    for (q in listOf(t2v(0.75f, -0.55f, 0.0f), t2v(0.25f, 0.3f, 1.1f), t2v(-0.35f, 0.3f, 2.4f), t2v(0.3f, 0.3f, 3.7f)))
        g.ell(q, t2v(0.5f, 0f, 0f), t2v(0f, 0.4f, 0f), t2v(0f, 0f, 0.4f), T2_EXPORT, 1f, 6, 9)
    // rough ER membrane, continuous with the outer nuclear membrane, to port
    for (xm in floatArrayOf(-4.3f, -4.9f)) {
        glass.surf(10, 12, T2_ER, 0.55f) { v, u -> t2s(xm, -12f + 24f * u, 4.4f + 18f * v) }
        var yy = -11.6f; while (yy < 12f) { var zz = 4.8f; while (zz < 22f) { heads.addAll(listOf(T2_SDX + xm + (if (xm < -4.6f) -0.02f else 0.02f), yy + T2_SDY, zz, 1f, 0.8f, 0.5f, 0.7f)); zz += 0.8f }; yy += 0.8f }
    }
    // ribosomes on Sec61: the large (60S) subunit against the translocon, the small (40S) outward
    for (rz in floatArrayOf(6f, 9.6f)) {
        g.ell(t2s(-3.0f, -0.9f, rz), t2v(1.2f, 0f, 0f), t2v(0f, 1.45f, 0f), t2v(0f, 0f, 1.35f), T2_RIBO_60S, 1f, 9, 12)
        g.ball(t2s(-2.4f, 0.5f, rz - 0.2f), 0.5f, T2_RIBO_60S, 1f, 5, 7)
        g.ball(t2s(-2.8f, -1.0f, rz + 1.35f), 0.42f, T2_RIBO_60S, 1f, 5, 7)
        g.ell(t2s(-1.0f, -0.8f, rz), t2v(0.75f, 0f, 0f), t2v(0f, 1.15f, 0f), t2v(0f, 0f, 1.2f), T2_RIBO_40S, 1f, 8, 11)
        g.ball(t2s(-0.9f, 0.5f, rz + 0.2f), 0.55f, T2_RIBO_40S, 1f, 5, 7)
        g.tube(t2s(-4.2f, -0.9f, rz), t2s(-5.0f, -0.9f, rz), 0.6f, 0.6f, T2_SEC61, 1f, 10, true)
        g.torus(t2s(-4.28f, -0.9f, rz), t2v(1f, 0f, 0f), 0.7f, 0.1f, T2_SEC61_RIM, 1f, 16, 5)
    }
    // the second ribosome's product, already folded in the lumen, with a chaperone
    g.ball(t2s(-6.1f, -0.8f, 9.9f), 0.42f, T2_CHAIN_B, 1f, 6, 8); g.ball(t2s(-6.0f, 0.05f, 10.4f), 0.45f, T2_BIP, 1f, 6, 8)
    for (q in 0 until 6) g.ball(t2s(-5.0f - q * 0.18f, -0.9f + 0.05f * q, 9.6f + 0.04f * q), 0.1f, if (q % 2 == 0) T2_CHAIN_A else T2_CHAIN_B, 1f, 3, 4)
    // a COPII-coated vesicle budding from the ER, folded proinsulin inside, bound for the Golgi
    run {
        val c = t2s(-2.2f, 6.2f, 16f); val r = 3.8f
        glass.ball(c, r, T2_COPII_VES, 0.3f, 12, 18)
        for (k in 0 until 30) { val y = 1f - 2f * (k + 0.5f) / 30f; val rr = sqrt(1f - y * y); val a = k * 2.39996f; g.ball(c + t2v(cos(a) * rr, y, sin(a) * rr) * (r + 0.2f), 0.35f, T2_COPII, 1f, 4, 6) }
        for (k in 0 until 3) g.ball(c + t2v(cos(k * 2.1f), 0.3f * k - 0.3f, sin(k * 2.1f)) * 1.4f, 0.6f, T2_CHAIN_B, 1f, 6, 8)
    }
    // the cis face of a Golgi cisterna, far ahead: where the vesicle is headed
    val gol = T2Geo()      // (its own mesh: faint until the craft is through the pore)
    run {
        val c = t2v(-20f, 9f, 30f); val nrm = t2v(0.6f, -0.3f, -1f).unit(); val e1 = t2perp(nrm); val e2 = nrm cross e1     // off the pore's line of sight
        gol.surf(10, 24, T2_GOLGI_CIS) { u, v ->
            val rho = if (u < 0.5f) u * 2f else (1f - u) * 2f; val side = if (u < 0.5f) 0.6f else -0.6f
            val a = v * 2f * T2PI
            c + (e1 * cos(a) + e2 * sin(a)) * (rho * 9f) + nrm * (side * sqrt(max(0f, 1f - rho * rho)) + 3f * rho * rho)
        }
    }
    val bend0 = t2Bend(i, 5f, 12f); val bend = { o: FloatArray, k: Int -> o[k + 2] += T2_FZ; bend0(o, k) }
    val hA = heads.toFloatArray(); for (k in 0 until hA.size / 7) bend(hA, k * 7)
    val fA = fg.toFloatArray(); for (k in 0 until fA.size / 7) bend(fA, k * 7)
    val lA = lam.toFloatArray(); for (k in 0 until lA.size / 7) bend(lA, k * 7)
    // depth cues: the nuclear side (below the inner membrane) tinted cool, the cytoplasmic ER
    // stage beyond the pore a fifth brighter, so the eye separates the two sides of the pore
    g.tint({ x, _, z -> z < 0.1f }, 0.8f, 0.86f, 1.08f)
    g.tint({ x, _, z -> z > 5.2f && x < -0.6f }, 1.2f, 1.2f, 1.2f)
    arrayOf(TriMesh(g.baked(bend)), TriMesh(glass.baked(bend)), PointMesh(hA), LineMesh(fA), LineMesh(lA), TriMesh(gol.baked(bend)))
}

/** The insulin granule and the plasma membrane it fuses with (far ahead-right; rigid frame). */
private fun StereoBodyRenderer.t2GranuleMeshes(i: Int): Array<ColorVboMesh> = t2Get("granule$i") {
    val glass = T2Geo(); val core = ArrayList<Float>(); val rnd = java.util.Random(89L)
    val gcx = 22f; val gcy = 8f; val gcz = 55f
    glass.surf(18, 28, T2_GRANULE, 0.26f) { u, v -> val th = u * T2PI; val ph = v * 2f * T2PI; t2v(gcx + sin(th) * cos(ph) * 15f, gcy + cos(th) * 15f, gcz + sin(th) * sin(ph) * 15f) }
    repeat(2200) {   // the dense core: a crystal of zinc-insulin hexamers
        var x: Float; var y: Float; var z: Float
        do { x = rnd.nextFloat() * 2f - 1f; y = rnd.nextFloat() * 2f - 1f; z = rnd.nextFloat() * 2f - 1f } while (x * x + y * y + z * z > 1f)
        core.addAll(listOf(gcx + 0.5f + x * 9f, gcy + y * 9f, gcz + z * 9f, 0.98f, 0.9f, 0.5f, 0.9f))
    }
    glass.surf(10, 14, T2_PM, 0.3f) { u, v -> t2v(37.3f, -12f + 40f * u, 30f + 54f * v) }
    val rigid0 = t2Bend(i, 0f, 0f); val rigid = { o: FloatArray, k: Int -> o[k + 2] += T2_FZ; rigid0(o, k) }
    val cA = core.toFloatArray(); for (k in 0 until cA.size / 7) rigid(cA, k * 7)
    arrayOf(TriMesh(glass.baked(rigid)), PointMesh(cA))
}

private fun StereoBodyRenderer.t2Octa(x: Float, y: Float, z: Float, r: Float, c: FloatArray, a: Float, n: Int): Int {
    // an octahedron with baked shading into t2Tris at vertex index n; returns the new index
    val d = t2Tris.data
    val px = floatArrayOf(r, -r, 0f, 0f, 0f, 0f); val py = floatArrayOf(0f, 0f, r, -r, 0f, 0f); val pz = floatArrayOf(0f, 0f, 0f, 0f, r, -r)
    val faces = intArrayOf(0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4, 2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5)
    var v = n
    for (f in 0 until 8) {
        val i0 = faces[f * 3]; val i1 = faces[f * 3 + 1]; val i2 = faces[f * 3 + 2]
        val nx = (px[i0] + px[i1] + px[i2]); val ny = (py[i0] + py[i1] + py[i2]); val nz = (pz[i0] + pz[i1] + pz[i2])
        val sh = 0.5f + 0.5f * abs(nx * 0.3f + ny * 0.83f - nz * 0.47f) / (r * 1.0f)
        for (q in intArrayOf(i0, i1, i2)) {
            if (v >= 4000) return v
            val o = v * 7
            d[o] = x + px[q]; d[o + 1] = y + py[q]; d[o + 2] = z + pz[q]
            d[o + 3] = min(1f, c[0] * sh); d[o + 4] = min(1f, c[1] * sh); d[o + 5] = min(1f, c[2] * sh); d[o + 6] = a
            v++
        }
    }
    return v
}

/** Tour II stop 10: gene to granule — polymerase, pore, ribosome on the ER, signal peptide, insulin exocytosis. */
internal fun StereoBodyRenderer.drawFactory(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val m = t2FactoryMeshes(i)
        t2DrawWorld(m[0])
        // RNA polymerase II crawling along the gene (a 30 s pass), its transcript joining the strand
        run {
            val t = (seconds % 30f) / 30f
            val px = 0.4f + 1.4f * t; val al = t2sm(t / 0.06f) * (1f - t2sm((t - 0.94f) / 0.06f))
            val fr = t2Frame(i, T2_FZ + -1.6f)
            // Pol II clamps the DNA in its cleft: two jaws above and below the helix axis, translucent
            // enough to show the transcription bubble inside, the strands parted over 0.5 units
            for (sd in SIGNS) {
                val p = t2W(fr, px, -2.1f + sd * 0.5f).copyOf()
                t2Basis(p, fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], 0.95f, 0.36f, 0.85f, sphere, T2_POL2, T2_POL2, 0.9f * al, 0.3f)
            }
            t2LinesBegin()
            for (st in 0..1) {
                var p0x = 0f; var p0y = 0f; var p0z = 0f
                for (q in 0..10) {
                    val x = px - 0.35f + 0.07f * q; val open = sin(T2PI * q / 10f) * 0.25f
                    val w = t2W(fr, x, -2.1f + (if (st == 0) open else -open))
                    if (q > 0) t2Seg(p0x, p0y, p0z, w[0], w[1], w[2], if (st == 0) T2_DNA else T2_SIGNAL, al)
                    p0x = w[0]; p0y = w[1]; p0z = w[2]
                }
            }
            t2LinesEnd(3f)
            val q = t2W(t2Frame(i, T2_FZ + -0.6f), 1.2f, -1.4f).copyOf()
            val e = t2W(t2Frame(i, T2_FZ + -1.6f), px + 0.1f, -1.85f).copyOf()
            drawStrut(e[0], e[1], e[2], q[0], q[1], q[2], 0.07f, T2_MRNA, T2_MRNA)
        }
        // the nascent chain (24 s): out of the large subunit's tunnel, through Sec61, signal peptide
        // first; signal peptidase cuts it off; BiP holds the chain while it folds.
        run {
            val t = seconds % 24f
            val nb = min(34, (t / 0.42f).toInt())
            val cut = t2sm((t - 15f) / 1.5f); val fold = t2sm((t - 16f) / 4f)
            val al = 1f - t2sm((t - 22.5f) / 1.5f)
            var v = 0
            val rnd = java.util.Random(3L)
            for (j in 0 until nb) {
                val s = (nb - 1 - j) * 0.28f          // j = 0 is the N-terminus, farthest along
                var x: Float; var y: Float; var z: Float
                if (s < 1.8f) { x = -3.9f - s + T2_SDX; y = -0.9f + T2_SDY; z = 6f }
                else { val u = s - 1.8f; x = -5.7f + T2_SDX - 0.35f * sin(u * 1.3f); y = -0.9f + T2_SDY + 0.8f * sin(u * 0.9f); z = 6f + 0.9f * cos(u * 0.8f) - 0.9f }
                val sig = j < 8
                if (sig) { x += cut * 0.4f; z -= cut * 1.2f }
                else if (j >= 8 && fold > 0f) { val fx = -6.2f + T2_SDX + (rnd.nextFloat() - 0.5f) * 0.9f; val fy = -1.0f + T2_SDY + (rnd.nextFloat() - 0.5f) * 0.9f; val fz = 6.4f + (rnd.nextFloat() - 0.5f) * 0.9f
                    x += (fx - x) * fold; y += (fy - y) * fold; z += (fz - z) * fold }
                val w = t2W(t2Frame(i, T2_FZ + z), x, y)
                val threading = sig && s > 0.4f && s < 2.2f && cut < 0.1f
                val c = if (threading) T2_SIGNAL_GLOW else if (sig) T2_SIGNAL else if (j % 2 == 0) T2_CHAIN_A else T2_CHAIN_B
                v = t2Octa(w[0], w[1], w[2], 0.12f, c, al * (if (sig) 1f - cut * 0.7f else 1f), v)
            }
            if (v > 0) { Matrix.setIdentityM(model, 0); Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
                colorShader.use(mvp, 1f); GLES20.glDisable(GLES20.GL_CULL_FACE); t2Tris.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_TRIANGLES, v); GLES20.glEnable(GLES20.GL_CULL_FACE) }
            val fr = t2Frame(i, T2_FZ + 6.6f)
            t2Blob(fr, -5.15f + T2_SDX, 0.1f + T2_SDY, 0.34f, 0.3f, 0.34f, T2_TONSIL, COL_LAMP, 1f, if (t in 14.8f..16.4f) 1.2f else 0.05f)   // signal peptidase
            if (t in 9f..22f) t2Blob(t2Frame(i, T2_FZ + 6.9f), -6.3f + T2_SDX, -0.2f + T2_SDY, 0.45f, 0.45f, 0.45f, T2_BIP, COL_LAMP, t2sm((t - 9f) / 1f) * al, 0.05f)
            if (t in 19f..22f) for (q in 0 until 3) t2Blob(t2Frame(i, T2_FZ + 6.2f + q * 0.25f), -6.1f + T2_SDX + q * 0.1f, -1.1f + T2_SDY + q * 0.2f, 0.08f, 0.08f, 0.08f, T2_SIGNAL, T2_SIGNAL, al, 1.5f)   // disulfide bonds forming
        }
        t2DrawWorld(m[3], true)
        lineWidth(2f); t2DrawWorld(m[4], true); lineWidth(1f)
        run {   // the cis-Golgi far ahead: a faint shape through the pore, full once we are through
            val keepF = colorShader.globalFade
            colorShader.globalFade = keepF * (0.2f + 0.8f * t2sm((routeProgress - i - 0.1f) / 0.2f))
            t2DrawWorld(m[5], true)
            colorShader.globalFade = keepF
        }
        t2DrawWorld(m[1], true)
        t2DrawWorld(m[2], true, 2f, true)
        if (own) {
            // exocytosis far ahead: the granule's membrane fuses with the plasma membrane, the pore
            // widens, hexamers stream out and fall apart into monomers (a 20 s cycle)
            val gm = t2GranuleMeshes(i)
            // the granule is far across the cytoplasm at the plasma membrane: faint while we are
            // still at the nuclear side, full once we are through the pore
            val keepF = colorShader.globalFade; val keepL = landmarkFade
            val far = t2sm((routeProgress - i - 0.1f) / 0.2f)
            colorShader.globalFade = keepF * far; landmarkFade = keepL * far
            t2DrawWorld(gm[1], true, 3f, true)
            t2DrawWorld(gm[0], true)
            val f0 = t2Rigid(i, 0f, FloatArray(13))
            val t = (seconds % 20f) / 20f
            val rho = 0.4f + 3.6f * t2sm(t / 0.35f)
            val fp = t2W(f0, 37.3f, 8f, 55f + T2_FZ).copyOf()
            t2Basis(fp, f0[6], f0[7], f0[8], f0[9], f0[10], f0[11], rho, rho, 6f, t2RingMesh(), T2_PM, COL_LAMP, 0.8f, 0.3f)
            t2LinesBegin()
            for (k in 0 until 40) {
                val tk = (t * 1.6f - k * 0.02f); if (tk < 0.15f || tk > 1.3f) continue
                val u = (tk - 0.15f) / 1.15f
                val a = k * 2.4f; val spread = u * 7f
                val x = 37.3f + u * 9f; val y = 8f + cos(a) * min(rho * 0.8f, 3f) + sin(a) * spread * 0.6f; val z = 55f + sin(a) * min(rho * 0.8f, 3f) + cos(a) * spread * 0.6f
                val split = t2sm((u - 0.4f) / 0.2f)
                for (sd in SIGNS) {
                    val w = t2W(f0, x + sd * split * 0.6f, y + sd * split * 0.4f, z + T2_FZ)
                    t2Vert(w[0], w[1], w[2], T2_INSULIN, 1f - t2sm((u - 0.8f) / 0.2f))
                }
            }
            t2LinesEnd(1f, true, 4f)
            colorShader.globalFade = keepF; landmarkFade = keepL
        }
    }
}

// ============================================================================ stop 10: MOTOR
// 0.8 nm a unit (Mote 1.2 nm). One ATP synthase in the inner membrane of a heart-muscle
// mitochondrion, built to scale around the craft: a 4-nm bilayer below (matrix side up), the
// c8 ring (~5 nm) in it, the central stalk (γδε) rising through the 4.5 nm gap into the α3β3
// head (~10 nm) overhead, the peripheral stalk from subunit a up the side to OSCP on top.
// Protons pass from the intermembrane space (below) through subunit a's entry half-channel
// onto a c subunit, ride the ring almost a full turn, and leave through the exit half-channel
// into the matrix: the rotor turns counter-clockwise seen from the matrix (synthesis), one ATP
// released per 120 degrees — 8 protons, 3 ATP per turn (slowed ~800x).

private const val T2_MEM_TOP = -3.0f
private const val T2_MEM_BOT = -8.0f
private const val T2_AXX = -3.2f
private const val T2_AXZ = 23.0f
private const val T2_SUBA = -2.356f     // subunit a's angle round the ring (+x toward +z): front, away from the lane

private fun StereoBodyRenderer.t2MotorMeshes(i: Int): Array<ColorVboMesh> = t2Get("motor$i") {
    val mem = T2Geo(); val win = T2Geo(); val stat = T2Geo(); val rot = T2Geo(); val glass = T2Geo(); val tails = ArrayList<Float>(); val far = ArrayList<Float>(); val rnd = java.util.Random(97L)
    // ---- the lipid bilayer: headgroups 0.8 nm apart on both faces, tails into the middle
    val ax = T2_AXX; val az = T2_AXZ
    val sax = ax + cos(T2_SUBA) * 3.7f; val saz = az + sin(T2_SUBA) * 3.7f
    var x = -14f
    while (x <= 16f) {
        var z = -8f
        while (z <= 30f) {
            val jx = x + (rnd.nextFloat() - 0.5f) * 0.3f; val jz = z + (rnd.nextFloat() - 0.5f) * 0.3f
            val dr = sqrt((jx - ax) * (jx - ax) + (jz - az) * (jz - az)); val da = sqrt((jx - sax) * (jx - sax) + (jz - saz) * (jz - saz))
            if (dr > 3.3f && da > 2.0f) for (yh in floatArrayOf(T2_MEM_TOP, T2_MEM_BOT)) {
                // round the enzyme the matrix-side leaflet is drawn see-through, so the c ring,
                // subunit a and the protons in the membrane can be seen
                if (yh == T2_MEM_TOP && dr < 7.5f) win.ball(t2v(jx, yh, jz), 0.3f, T2_LIPID_HEAD, 0.3f, 3, 5)
                else mem.ball(t2v(jx, yh, jz), 0.3f, T2_LIPID_HEAD, 1f, 3, 5)
                val dir = if (yh > -5f) -1f else 1f
                for (tl in 0..1) { val ox = (tl - 0.5f) * 0.3f; var py = yh
                    for (q in 1..4) { val ny = yh + dir * q * 0.55f; val wx = jx + ox + 0.08f * sin(q * 2f + tl)
                        tails.addAll(listOf(jx + ox + 0.08f * sin((q - 1) * 2f + tl), py, jz, 0.5f, 0.86f, 0.8f, 0.7f, wx, ny, jz, 0.5f, 0.86f, 0.8f, 0.7f)); py = ny } }
            }
            z += 1.0f
        }
        x += 1.0f
    }
    x = -34f
    while (x <= 36f) { var z = -26f; while (z <= 44f) { if (x < -14.5f || x > 16.5f || z < -8.5f || z > 30.5f) for (yh in floatArrayOf(T2_MEM_TOP, T2_MEM_BOT)) far.addAll(listOf(x, yh, z, 1f, 0.8f, 0.5f, 0.85f)); z += 1f }; x += 1f }
    // ---- rotor (local, axis at the origin, +y up to the matrix): c8 ring, δε foot, γ
    for (k in 0 until 8) {
        val a = k * T2PI / 4f; val c = if (k == 0) T2_C_MARK else T2_C_RING
        val ci = t2v(cos(a) * 1.55f, 0f, sin(a) * 1.55f); val co = t2v(cos(a + 0.18f) * 2.55f, 0f, sin(a + 0.18f) * 2.55f)
        rot.tube(ci + t2v(0f, -8.5f, 0f), ci + t2v(0f, -2.5f, 0f), 0.45f, 0.45f, c, 1f, 7, true)
        rot.tube(co + t2v(0f, -8.6f, 0f), co + t2v(0f, -2.4f, 0f), 0.5f, 0.5f, c, 1f, 7, true)
        rot.tube(ci + t2v(0f, -2.4f, 0f), co + t2v(0f, -2.3f, 0f), 0.36f, 0.36f, c, 1f, 6, true)
        rot.ball(t2v(cos(a + 0.18f) * 3.02f, -5.5f, sin(a + 0.18f) * 3.02f), 0.2f, floatArrayOf(0.95f, 0.3f, 0.3f, 1f), 1f, 4, 6)   // the carrier glutamate
    }
    rot.ball(t2v(0f, -1.6f, 0f), 1.5f, T2_GAMMA, 1f, 8, 12)
    rot.path(listOf(t2v(0f, -1.2f, 0f), t2v(0.3f, 1.5f, 0f), t2v(0.55f, 4.0f, 0.1f), t2v(0.25f, 7.5f, 0f), t2v(0f, 10.8f, 0f)), { t -> 0.62f - 0.12f * t }, T2_GAMMA, 1f, 8, true)
    rot.ball(t2v(0.7f, 1.6f, 0.2f), 0.85f, T2_GAMMA, 1f, 6, 9)
    // ---- stator (static): α subunits of the head, subunit a in the membrane, peripheral stalk, OSCP
    for (k in 0 until 3) {
        val a = k * 2f * T2PI / 3f + T2PI / 3f
        stat.ell(t2v(ax + cos(a) * 3.4f, 8.0f, az + sin(a) * 3.4f), t2v(2.6f, 0f, 0f), t2v(0f, 4.6f, 0f), t2v(0f, 0f, 2.6f), T2_ALPHA, 1f, 10, 14)
    }
    stat.ellAxis(t2v(sax, -5.5f, saz), t2v(0f, 1f, 0f), 1.3f, 3.5f, 2.3f, T2_SUB_A, 1f, 9, 12, t2v(cos(T2_SUBA), 0f, sin(T2_SUBA)))
    val dA = t2v(cos(T2_SUBA), 0f, sin(T2_SUBA))
    val stalk = listOf(t2v(ax, -2f, az) + dA * 4.4f, t2v(ax, 3f, az) + dA * 6.3f, t2v(ax, 8f, az) + dA * 6.7f, t2v(ax, 12.6f, az) + dA * 5.4f, t2v(ax, 14.2f, az) + dA * 2.4f)
    stat.path(stalk, { 0.8f }, T2_STATOR, 1f, 10, true)
    stat.ball(t2v(ax, 6.2f, az) + dA * 6.6f, 0.95f, T2_STATOR, 1f, 6, 9)
    stat.ball(t2v(ax, 14.4f, az), 1.6f, T2_STATOR, 1f, 8, 12)
    // the two half-channels in subunit a, at the a/c interface: entry from below, exit to above
    for ((sd, y0, y1) in listOf(Triple(0.3f, -9f, -5.5f), Triple(-0.3f, -5.5f, -2.2f))) {
        val a = T2_SUBA + sd; val p = t2v(ax + cos(a) * 3.45f, 0f, az + sin(a) * 3.45f)
        glass.tube(p + t2v(0f, y0, 0f), p + t2v(0f, y1, 0f), 0.36f, 0.36f, T2_PROTON, 0.35f, 8, true)
    }
    val rigid = t2Bend(i, 0f, 0f)
    val tA = tails.toFloatArray(); for (k in 0 until tA.size / 7) rigid(tA, k * 7)
    val fA = far.toFloatArray(); for (k in 0 until fA.size / 7) rigid(fA, k * 7)
    arrayOf(TriMesh(mem.baked(rigid)), TriMesh(stat.baked(rigid)), TriMesh(rot.baked()), LineMesh(tA), PointMesh(fA), TriMesh(glass.baked(rigid)), TriMesh(win.baked(rigid)))
}

/** ADP (adenine, ribose, two phosphates) and, separately, inorganic phosphate. */
private fun StereoBodyRenderer.t2AdpMesh(): TriMesh = t2Get("adp") {
    val g = T2Geo()
    g.ball(t2v(-0.75f, 0.1f, 0f), 0.3f, T2_ADENINE, 1f, 5, 7); g.ball(t2v(-0.35f, 0.25f, 0f), 0.26f, T2_ADENINE, 1f, 5, 7)
    g.ball(t2v(0.05f, -0.1f, 0f), 0.26f, T2_LIPID_TAIL, 1f, 5, 7)
    for (k in 0 until 2) g.ball(t2v(0.4f + k * 0.34f, -0.15f + 0.08f * (k % 2), 0f), 0.2f, T2_PHOSPHATE, 1f, 5, 7)
    TriMesh(g.baked())
}
private fun StereoBodyRenderer.t2PiMesh(): TriMesh = t2Get("pi") { val g = T2Geo(); g.ball(t2v(0f, 0f, 0f), 0.2f, T2_PHOSPHATE, 1f, 5, 7); TriMesh(g.baked()) }

/** The proton gradient: a crowd in the intermembrane space below, a sparse few in the matrix above. */
private fun StereoBodyRenderer.t2ProtonCloud(i: Int): TriMesh = t2Get("hcloud$i") {
    val g = T2Geo(); val rnd = java.util.Random(211L)
    repeat(40) { val a = rnd.nextFloat() * 2f * T2PI; val r = 1.5f + 8f * sqrt(rnd.nextFloat()); g.ball(t2v(T2_AXX + cos(a) * r, -9.5f - 3.5f * rnd.nextFloat(), T2_AXZ + sin(a) * r), 0.2f, T2_PROTON, 0.8f, 4, 6) }
    repeat(6) { val a = rnd.nextFloat() * 2f * T2PI; val r = 4f + 5f * rnd.nextFloat(); g.ball(t2v(T2_AXX + cos(a) * r, 6f * rnd.nextFloat(), T2_AXZ + sin(a) * r), 0.2f, T2_PROTON, 0.8f, 4, 6) }
    TriMesh(g.baked(t2Bend(i, 0f, 0f)))
}

private fun StereoBodyRenderer.t2AtpMesh(): TriMesh = t2Get("atp") {
    // ATP, ~1.4 nm: adenine (two fused rings), ribose, and a chain of three phosphates
    val g = T2Geo()
    g.ball(t2v(-0.75f, 0.1f, 0f), 0.3f, T2_ADENINE, 1f, 5, 7); g.ball(t2v(-0.35f, 0.25f, 0f), 0.26f, T2_ADENINE, 1f, 5, 7)
    g.ball(t2v(0.05f, -0.1f, 0f), 0.26f, T2_LIPID_TAIL, 1f, 5, 7)
    for (k in 0 until 3) g.ball(t2v(0.4f + k * 0.34f, -0.15f + 0.08f * (k % 2), 0f), 0.2f, T2_PHOSPHATE, 1f, 5, 7)
    TriMesh(g.baked())
}

/** Tour II stop 11: ATP synthase running forward: protons down their gradient turn the rotor, the head makes ATP. */
internal fun StereoBodyRenderer.drawMotor(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2MotorMeshes(i)
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        t2DrawWorld(m[0]); t2DrawWorld(m[1]); t2DrawWorld(m[3], true); t2DrawWorld(m[4], true, 3f, true)
        val omega = 2f * T2PI / 8f             // one turn in 8 s
        val th = seconds * omega               // counter-clockwise seen from the matrix (+y)
        t2Model(f0, T2_AXX, 0f, T2_AXZ, th); t2Draw(m[2])
        // β subunits: each cycles open (empty) -> loose (ADP + Pi) -> tight (ATP) as γ turns;
        // the one opening releases its ATP, glowing.
        val third = 2f * T2PI / 3f
        for (k in 0 until 3) {
            val a = k * third
            val rel = ((th - a) % third + third) % third / third            // progress through this 120° step
            val state = (floor((th - a) / third).toInt() % 3 + 3) % 3
            val open = if (state == 0) 1f else 0f
            val sc = if (state == 2) 0.93f else 1f
            val p = t2W(f0, T2_AXX + cos(a) * (3.4f + 0.3f * open), 8.0f - 0.2f * open, T2_AXZ + sin(a) * (3.4f + 0.3f * open)).copyOf()
            val glow = if (state == 0) 0.6f * (1f - t2sm(rel / 0.35f)) else 0.05f
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 2.6f * sc, 4.6f * sc, 2.6f * sc, sphere, T2_BETA, T2_ALPHA, 1f, glow)
            if (state == 1 && rel < 0.4f) {     // ADP and Pi arriving from the matrix into the open site
                val u = t2sm(rel / 0.3f); val d = 3.4f + 1.6f + 4f * (1f - u)
                t2Model(f0, T2_AXX + cos(a) * d, 7.0f + 2f * (1f - u), T2_AXZ + sin(a) * d, a); t2Draw(t2AdpMesh())
                t2Model(f0, T2_AXX + cos(a + 0.25f) * (d + 0.3f), 7.4f + 2.5f * (1f - u), T2_AXZ + sin(a + 0.25f) * (d + 0.3f)); t2Draw(t2PiMesh())
            }
            if (state == 0 && rel < 0.95f) {    // the ATP just released drifts out into the matrix
                val d = 3.4f + 3.0f + rel * 6f
                t2Model(f0, T2_AXX + cos(a) * d, 5.5f + rel * 3f, T2_AXZ + sin(a) * d, rel * 4f)
                val keep = colorShader.globalFade; colorShader.globalFade = keep * (1f - t2sm((rel - 0.7f) / 0.25f)); t2Draw(m[2].let { t2AtpMesh() }); colorShader.globalFade = keep
            }
        }
        // protons (hydronium, ~0.3 nm): arriving from the intermembrane space, riding the c ring,
        // leaving into the matrix
        val entry = T2_SUBA + 0.3f; val exit = T2_SUBA - 0.3f
        val q = FloatArray(3)
        for (k in 0 until 8) {
            val phi = th + k * T2PI / 4f + 0.18f
            val sinceEntry = ((phi - entry) % (2f * T2PI) + 2f * T2PI) % (2f * T2PI)
            val sinceExit = ((phi - exit) % (2f * T2PI) + 2f * T2PI) % (2f * T2PI)
            val toEntry = 2f * T2PI - sinceEntry
            if (sinceEntry < 2f * T2PI - 0.6f) {   // bound to this subunit's glutamate
                t2W(f0, T2_AXX + cos(phi) * 3.1f, -5.5f, T2_AXZ + sin(phi) * 3.1f).copyInto(q)
                t2Basis(q, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROTON, T2_PROTON, 1f, 0.35f)
            }
            val tIn = toEntry / omega
            if (tIn < 3f) {   // arriving: up from the IMS into the entry half-channel
                val u = tIn / 3f
                val cx = T2_AXX + cos(entry) * 3.45f; val cz = T2_AXZ + sin(entry) * 3.45f
                val px: Float; val py: Float; val pz: Float
                if (u < 0.35f) { val s = u / 0.35f; px = cx; py = -5.5f - 3.5f * s; pz = cz }
                else { val s = (u - 0.35f) / 0.65f; px = cx + cos(entry + k) * 5f * s; py = -9f - 3f * s; pz = cz + sin(entry + k) * 5f * s }
                t2W(f0, px, py, pz).copyInto(q)
                t2Basis(q, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROTON, T2_PROTON, 1f - t2sm((u - 0.85f) / 0.15f), 0.35f)
            }
            val tOut = sinceExit / omega
            if (tOut < 3f) {  // leaving: up the exit half-channel into the matrix
                val u = tOut / 3f
                val cx = T2_AXX + cos(exit) * 3.45f; val cz = T2_AXZ + sin(exit) * 3.45f
                val px: Float; val py: Float; val pz: Float
                if (u < 0.35f) { val s = u / 0.35f; px = cx; py = -5.5f + 3.3f * s; pz = cz }
                else { val s = (u - 0.35f) / 0.65f; px = cx + cos(exit - k) * 4f * s; py = -2.2f + 2.4f * s; pz = cz + sin(exit - k) * 4f * s }
                t2W(f0, px, py, pz).copyInto(q)
                t2Basis(q, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROTON, T2_PROTON, 1f - t2sm((u - 0.8f) / 0.2f), 0.35f)
            }
        }
        t2DrawWorld(m[5], true)
        t2DrawWorld(m[6], true)
        t2Model(f0, 0.4f * sin(seconds * 0.2f), 0.2f * sin(seconds * 0.27f), 0.4f * cos(seconds * 0.17f)); t2Draw(t2ProtonCloud(i), true)
    }
}

// ========================================================================= stop 11: DIVISION
// 0.8 µm a unit (Mote 1.2 µm). An intestinal crypt cell dividing in its epithelium: rounded up
// (~14 µm) toward the lumen among tall columnar neighbours (brush border up, nuclei low), its
// spindle parallel to the sheet. 46 chromosomes, each two sister chromatids joined at the
// centromere, congress to a ring-shaped metaphase plate (the craft passes through its hollow
// centre), then the sisters part and move poleward as V shapes (anaphase A) while the poles
// separate (anaphase B); nuclear envelopes re-form round the decondensing sets and an actomyosin
// ring pinches the cell in two, leaving a midbody (a 36 s cycle).

private const val T2_SPX = 4.0f
private const val T2_SPY = 0f         // spindle axis: parallel to the rail, 8.5 to starboard at eye level
private const val T2_PLATE_Z = 16.0f
private const val T2_CELL_R = 7.5f      // the rounded mitotic cell, 12 µm
private const val T2_APICAL = 5f       // apical surface of the starboard crypt wall (x) where the cell sits
private const val T2_CRYPT_R = 14f      // the crypt lumen: a tube of this radius round the rail's line

private class T2Chromo(val cx: Float, val cy: Float, val wx: Float, val wy: Float, val lp: Float, val lq: Float, val sx: Float, val sy: Float, val sz: Float)

private val t2Chromos: List<T2Chromo> by lazy {
    val rnd = java.util.Random(131L); val out = ArrayList<T2Chromo>()
    for (k in 0 until 46) {
        val big = k < 8; val mid = k in 8 until 22
        val lp = if (big) 1.8f + rnd.nextFloat() * 0.4f else if (mid) 1.2f + rnd.nextFloat() * 0.3f else 0.55f + rnd.nextFloat() * 0.35f
        val lq = if (big) 2.6f + rnd.nextFloat() * 0.4f else if (mid) 1.8f + rnd.nextFloat() * 0.5f else 1.0f + rnd.nextFloat() * 0.5f
        // a filled rosette: the smallest chromosomes near the centre of the plate, the big ones round the rim
        val ang = (k * 2.39996f) % (2f * T2PI)
        var rad = if (k >= 34) 0.4f + (k - 34) / 11f * 1.6f else 2.3f + ((k * 0.61803f) % 1f) * 2.6f
        val q = (rnd.nextFloat() - 0.5f) * 0.8f + T2PI * 0.5f     // arms pointing outward
        val tx = -sin(ang); val ty = cos(ang); val rx = cos(ang); val ry = sin(ang)
        val wx = tx * cos(q) + rx * sin(q); val wy = ty * cos(q) + ry * sin(q)
        // keep the arm tips inside the cell
        val cx0 = cos(ang) * rad; val cy0 = sin(ang) * rad
        val tip = max(sqrt((cx0 + wx * lq).pow(2) + (cy0 + wy * lq).pow(2)), sqrt((cx0 - wx * lp).pow(2) + (cy0 - wy * lp).pow(2)))
        if (tip > 6.6f) rad -= (tip - 6.6f)
        out.add(T2Chromo(cos(ang) * max(0.3f, rad), sin(ang) * max(0.3f, rad), wx, wy, lp, lq, (rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 6f))
    }
    out
}

// A chromatid arm as a tapered, round-tipped tube: rings along its length (0 = centromere).
private val T2_ARM_T = floatArrayOf(0f, 0.12f, 0.4f, 0.8f, 0.94f, 1f)
private val T2_ARM_R = floatArrayOf(0.6f, 0.95f, 1f, 0.98f, 0.72f, 0f)

private fun t2Arm(n0: Int, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, len: Float, r: Float, col: FloatArray, al: Float): Int {
    val d = t2ChromTris.data; var v = n0
    // perpendicular basis
    var px = -dy; var py = dx; var pz = 0f
    var pl = sqrt(px * px + py * py)
    if (pl < 1e-4f) { px = 1f; py = 0f; pz = 0f; pl = 1f }
    px /= pl; py /= pl; pz /= pl
    val qx = dy * pz - dz * py; val qy = dz * px - dx * pz; val qz = dx * py - dy * px
    val sides = 6
    for (ri in 0 until 5) for (s in 0 until sides) {
        if (v + 6 > 36000) return v
        val a0 = s * 2f * T2PI / sides; val a1 = (s + 1) * 2f * T2PI / sides
        for ((rk, aa) in arrayOf(ri to a0, ri + 1 to a0, ri + 1 to a1, ri to a0, ri + 1 to a1, ri to a1)) {
            val ca = cos(aa); val sa = sin(aa); val rr = T2_ARM_R[rk] * r; val t = T2_ARM_T[rk] * len
            val nx = px * ca + qx * sa; val ny = py * ca + qy * sa; val nz = pz * ca + qz * sa
            val sh = 0.5f + 0.55f * abs(nx * 0.3f + ny * 0.83f - nz * 0.47f)
            val o = v * 7
            d[o] = cx + dx * t + nx * rr; d[o + 1] = cy + dy * t + ny * rr; d[o + 2] = cz + dz * t + nz * rr
            d[o + 3] = min(1f, col[0] * sh); d[o + 4] = min(1f, col[1] * sh); d[o + 5] = min(1f, col[2] * sh); d[o + 6] = al
            v++
        }
    }
    return v
}

/**
 * The crypt: columnar epithelium lining a tube of radius 14 (22 µm across) round the rail, apical
 * faces inward with short, sparse microvilli, nuclei in the basal third; three Paneth cells with
 * their apical secretory granules further along; a gap where the dividing cell has rounded up.
 */
private fun StereoBodyRenderer.t2Epithelium(i: Int): Array<ColorVboMesh> = t2Get("epith$i") {
    val g = T2Geo(); val brush = ArrayList<Float>(); val rnd = java.util.Random(151L)
    val R = T2_CRYPT_R; val ax0 = T2_APICAL - R     // tube axis (x = ax0, y = 0), the wall's starboard side at x = 6
    val H = 26f
    val cols = 16; val rh = 2f * T2PI * R / cols / 1.5f
    fun P(arc: Float, z: Float, rr: Float): T2V { val th = arc / R; return t2v(ax0 + cos(th) * rr, sin(th) * rr, z) }
    var paneth = 0
    val outline = ArrayList<Float>(); val cMit = t2v(T2_SPX, T2_SPY, T2_PLATE_Z)
    for (row in -4..6) for (c in 0 until cols) {
        val ac = (c - cols / 2) * 1.5f * rh; val zc = T2_PLATE_Z + row * sqrt(3f) * rh + (if (c % 2 != 0) sqrt(3f) * rh * 0.5f else 0f)
        val bottom = P(ac, zc, R); val dc = (bottom - t2v(T2_SPX, T2_SPY, T2_PLATE_Z)).len()
        if (zc < -14f || zc > 42f) continue
        if (dc < T2_CELL_R + 1.2f + rh * 1.7f) {
            // this neighbour's apical outline, drawn on the continuous apical sheet round the gap
            for (k in 0 until 6) {
                val a0 = k * T2PI / 3f; val a1 = (k + 1) * T2PI / 3f
                for (q in 0 until 4) {
                    val t0 = q / 4f; val t1 = (q + 1) / 4f
                    val pa = P(ac + rh * (cos(a0) * (1 - t0) + cos(a1) * t0), zc + rh * (sin(a0) * (1 - t0) + sin(a1) * t0), R - 0.08f)
                    val pb = P(ac + rh * (cos(a0) * (1 - t1) + cos(a1) * t1), zc + rh * (sin(a0) * (1 - t1) + sin(a1) * t1), R - 0.08f)
                    if ((pa - cMit).len() < T2_CELL_R || (pb - cMit).len() < T2_CELL_R) continue
                    outline.addAll(listOf(pa.x, pa.y, pa.z, 0.78f, 0.58f, 0.58f, 1f, pb.x, pb.y, pb.z, 0.78f, 0.58f, 0.58f, 1f))
                }
            }
        }
        if (dc < T2_CELL_R + 1.2f) {
            // this neighbour lies under the sheet: only its microvilli, clear of the rounded cell
            repeat(45) {
                val u = (rnd.nextFloat() - 0.5f) * rh * 1.5f; val w = (rnd.nextFloat() - 0.5f) * rh * 1.5f
                val a = P(ac + u, zc + w, R - 0.05f); val b = P(ac + u, zc + w, R - 0.55f)
                if ((a - cMit).len() > T2_CELL_R + 0.2f && (b - cMit).len() > T2_CELL_R + 0.2f)
                    brush.addAll(listOf(a.x, a.y, a.z, 0.99f, 0.9f, 0.86f, 0.7f, b.x, b.y, b.z, 0.99f, 0.9f, 0.86f, 0.7f))
            }
            continue
        }
        val isPaneth = paneth < 3 && row == -2 && abs(c - cols / 2) <= 1
        if (isPaneth) paneth++
        val shrinkA = if (isPaneth) 2.5f / (2f * rh) else 0.96f; val shrinkB = if (isPaneth) 4.5f / (2f * rh) else 0.96f
        val cor = (0 until 6).map { k -> val a = k * T2PI / 3f; floatArrayOf(ac + cos(a) * rh, zc + sin(a) * rh) }
        // neighbours close round the rounded-up mitotic cell: any part of a cell inside it is pushed
        // out to its surface, so they lean over and hug it and the sheet stays continuous
        val cc = t2v(T2_SPX, T2_SPY, T2_PLATE_Z); val rc = T2_CELL_R + 0.25f
        var pushedAny = false
        fun push(q: T2V): T2V = q
        for (k in 0 until 6) {
            val a = cor[k]; val b = cor[(k + 1) % 6]
            fun Q(pt: FloatArray, rr: Float, sh: Float) = push(P(ac + (pt[0] - ac) * sh, zc + (pt[1] - zc) * sh, rr))
            val rows = 5
            for (q in 0 until rows) {
                val r0 = R + H * q / rows; val r1 = R + H * (q + 1) / rows
                val s0 = shrinkA + (shrinkB - shrinkA) * q / rows; val s1 = shrinkA + (shrinkB - shrinkA) * (q + 1) / rows
                val pa0 = Q(a, r0, s0); val pb0 = Q(b, r0, s0)
                g.quad(Q(a, r1, s1), Q(b, r1, s1), pb0, pa0, if (isPaneth) T2_PANETH else if (q == 0 && dc < rc + 3f) T2_EPI_BORDER else T2_EPITHELIUM, 1f)
            }
            g.tri(push(P(ac, zc, R)), Q(a, R, shrinkA), Q(b, R, shrinkA), if (isPaneth) T2_PANETH else T2_EPITHELIUM_TOP, 1f)
        }
        val nr = R + H - 7f + rnd.nextFloat() * 2f
        val nc = push(P(ac, zc, nr)); val out = (nc - t2v(ax0, 0f, nc.z)).unit()
        g.ellAxis(nc, out, 1.0f, 3.0f, 1.0f, T2_EPI_NUC, 1f, 6, 9)
        if (isPaneth) {   // eosinophilic secretory granules crowding the apex
            for (q in 0 until 10) g.ball(P(ac + (rnd.nextFloat() - 0.5f) * 1.6f, zc + (rnd.nextFloat() - 0.5f) * 1.6f, R + 0.6f + rnd.nextFloat() * 1.6f), 0.35f, T2_PANETH_GRAN, 1f, 5, 7)
        } else if (!pushedAny) repeat(45) {   // short, sparse microvilli (crypt cells), 0.4 µm
            val u = (rnd.nextFloat() - 0.5f) * rh * 1.5f; val w = (rnd.nextFloat() - 0.5f) * rh * 1.5f
            val a = P(ac + u, zc + w, R); val b = P(ac + u, zc + w, R - 0.5f)
            brush.addAll(listOf(a.x, a.y, a.z, 0.99f, 0.9f, 0.86f, 0.7f, b.x, b.y, b.z, 0.99f, 0.9f, 0.86f, 0.7f))
        }
    }
    // the sheet's apical surface, continuous round the rounded-up cell, and the socket it sits in
    // (the neighbours' faces bordering it), so no cut cell faces show
    val cc = t2v(T2_SPX, T2_SPY, T2_PLATE_Z); val hole = T2_CELL_R - 0.4f
    val sheet = T2Geo()
    sheet.surf(80, 120, T2_EPITHELIUM_TOP) { v, u ->
        val z = -14f + 56f * v; val th = (u - 0.5f) * 2f * T2PI * 0.999f
        t2v(ax0 + cos(th) * (R - 0.05f), sin(th) * (R - 0.05f), z)
    }
    // (only round the gap; further out the cells' own apical faces and outlines show)
    g.appendWhere(sheet) { x, y, z -> val d = (t2v(x, y, z) - cc).len(); d > hole && d < T2_CELL_R + 1.2f + rh * 1.6f }
    g.surf(12, 40, T2_EPITHELIUM) { v, u ->
        val a = u * 2f * T2PI; val th = 0.25f + 1.2f * v     // the socket: the far side of a sphere round the cell
        val dir = t2v(cos(th), sin(th) * cos(a), sin(th) * sin(a))
        cc + dir * (T2_CELL_R + 0.2f)
    }
    val rigid = t2Bend(i, 0f, 0f)
    brush.addAll(outline)
    val bA = brush.toFloatArray(); for (k in 0 until bA.size / 7) rigid(bA, k * 7)
    arrayOf(TriMesh(g.baked(rigid)), LineMesh(bA))
}

private var t2DivArrive = -1f
private var t2DivBuilt = -1f
private var t2DivT = -1f
private var t2DivVerts = 0

/** Tour II stop 12: mitosis in a gut-lining cell — prometaphase to cytokinesis, 46 chromosomes. */
internal fun StereoBodyRenderer.drawDivision(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        val epi = t2Epithelium(i)
        if (own) { t2DrawWorld(epi[0]); lineWidth(1.5f); t2DrawWorld(epi[1], true); lineWidth(1f) }
        // mitosis paced by the crew rather than a free loop: prometaphase on arrival, metaphase held
        // through "forty-six chromosomes...", anaphase on "...hauled apart", two cells by "And
        // there's two", then held
        if (routeProgress < i - 0.3f || routeProgress > i + 0.6f) t2DivArrive = -1f
        else if (t2DivArrive < 0f && routeProgress > i - 0.2f) t2DivArrive = seconds
        val tt = if (t2DivArrive < 0f) 0f else seconds - t2DivArrive
        val t = when { tt < 6f -> 1.2f + tt; tt < 22f -> 7.2f + (tt - 6f) * 8.8f / 16f; else -> min(33f, 16f + (tt - 22f) * 20f / 28f) }
        val cong = t2sm((t - 1f) / 6f); val anaA = t2sm((t - 16f) / 7f); val anaB = t2sm((t - 18f) / 6f)
        val telo = t2sm((t - 23f) / 4f); val cyto = t2sm((t - 23f) / 8f)
        val cycleA = t2sm(t / 1.2f) * (1f - t2sm((t - 34.5f) / 1.5f))
        val poleD = 5.5f + 1.2f * anaB
        // chromosomes, rebuilt at ~20 Hz (both eyes share one build)
        val sig = cong + anaA * 3f + anaB * 7f + telo * 13f + cycleA * 29f
        if (t2DivVerts == 0 || (abs(sig - t2DivBuilt) > 0.002f && abs(seconds - t2DivT) > 0.2f)) {
            t2DivBuilt = sig; t2DivT = seconds
            var v = 0
            val ch = t2Chromos
            val col = t2mix(T2_CHROMATID, T2_CHROMOSOME_LIGHT_T2, telo * 0.6f); val colB = floatArrayOf(col[0] * 0.8f, col[1] * 0.8f, col[2] * 0.8f, 1f)
            val al = (1f - 0.55f * t2sm((telo - 0.7f) / 0.3f)) * cycleA
            val r = 0.28f * (1f + 0.3f * telo); val shrink = 1f - 0.25f * telo
            val tw = FloatArray(3)
            for (c in ch) {
                for (sgn in SIGNS) {
                    val sc = 1f - cong
                    var x = T2_SPX + c.cx + c.sx * sc; var y = T2_SPY + c.cy + c.sy * sc
                    var z = T2_PLATE_Z + c.sz * sc + sgn * 0.34f + sgn * anaA * (poleD - 1.6f)
                    val conv = 1f - 0.45f * anaA - 0.2f * telo
                    x = T2_SPX + (x - T2_SPX) * conv; y = T2_SPY + (y - T2_SPY) * conv
                    val gam = T2PI * 0.5f * (1f - anaA) + 0.5f * anaA
                    val bk = -sgn
                    for (arm in 0..1) {
                        val s = if (arm == 0) -1f else 1f
                        val dx = c.wx * s * sin(gam); val dy = c.wy * s * sin(gam); val dz = bk * cos(gam)
                        val len = (if (arm == 0) c.lp else c.lq) * shrink
                        val w = t2W(f0, x, y, z); tw[0] = w[0]; tw[1] = w[1]; tw[2] = w[2]
                        // arm direction in world
                        val wx = f0[6] * dx + f0[9] * dy + f0[3] * dz; val wy = f0[7] * dx + f0[10] * dy + f0[4] * dz; val wz = f0[8] * dx + f0[11] * dy + f0[5] * dz
                        v = t2Arm(v, tw[0], tw[1], tw[2], wx, wy, wz, len, r, if (sgn > 0f) col else colB, al)
                    }
                }
            }
            t2DivVerts = v
        }
        if (t2DivVerts > 0) {
            Matrix.setIdentityM(model, 0); Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            colorShader.use(mvp, 1f); GLES20.glDisable(GLES20.GL_CULL_FACE)
            if (telo > 0.02f) GLES20.glDepthMask(false)
            t2ChromTris.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_TRIANGLES, t2DivVerts)
            GLES20.glDepthMask(true); GLES20.glEnable(GLES20.GL_CULL_FACE)
        }
        // spindle: kinetochore fibres to each sister's own pole, interpolar fibres overlapping at the
        // equator, astral fibres radiating from the poles to the cortex
        t2LinesBegin()
        val sp = 1f - t2sm((t - 26f) / 4f)
        if (sp > 0.02f) {
            for (sgn in SIGNS) {
                val pz = T2_PLATE_Z + sgn * poleD
                val pw = t2W(f0, T2_SPX, T2_SPY, pz); val px = pw[0]; val py = pw[1]; val pzz = pw[2]
                if (cong > 0.2f) for (c in t2Chromos) {
                    val sc = 1f - cong; val conv = 1f - 0.45f * anaA
                    val x = T2_SPX + (c.cx + c.sx * sc) * conv; val y = T2_SPY + (c.cy + c.sy * sc) * conv
                    val z = T2_PLATE_Z + c.sz * sc + sgn * 0.34f + sgn * anaA * (poleD - 1.6f)
                    val k = t2W(f0, x, y, z)
                    t2Seg(k[0], k[1], k[2], px, py, pzz, T2_SPINDLE, 0.4f * cong * sp, 0.15f * sp)
                }
                for (q in 0 until 20) {
                    val a = q * 2.4f; val rr = 0.8f + (q % 5) * 0.9f
                    val e = t2W(f0, T2_SPX + cos(a) * rr, T2_SPY + sin(a) * rr, T2_PLATE_Z - sgn * 1.0f)
                    t2Seg(px, py, pzz, e[0], e[1], e[2], T2_SPINDLE, 0.3f * sp, 0.12f * sp)
                }
                for (q in 0 until 16) {
                    val a = q * 2.4f; val el = 0.4f + (q % 4) * 0.25f
                    val e = t2W(f0, T2_SPX + cos(a) * 5f * el, T2_SPY + sin(a) * 5f * el, pz + sgn * 4.5f * (1f - el * 0.5f))
                    t2Seg(px, py, pzz, e[0], e[1], e[2], T2_SPINDLE, 0.3f * sp, 0.05f)
                }
            }
        }
        t2LinesEnd(1.5f)
        // centrosomes: a pair of centrioles at right angles in pericentriolar material
        for (sgn in SIGNS) {
            val pz = T2_PLATE_Z + sgn * poleD
            val p = t2W(f0, T2_SPX, T2_SPY, pz).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.4f, 0.4f, 0.4f, sphere, T2_CENTROSOME, COL_LAMP, 0.55f * cycleA, 0.4f)
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.1f, 0.1f, 0.25f, cylinder, T2_GTP_CAP, COL_LAMP, cycleA, 0.3f)
            t2Basis(p, f0[6], f0[7], f0[8], f0[9], f0[10], f0[11], 0.1f, 0.1f, 0.25f, cylinder, T2_GTP_CAP, COL_LAMP, cycleA, 0.3f)
        }
        // nuclear envelopes re-forming round each set (telophase)
        // (drawn as a light wire of meridians with a few pores, so the chromosomes stay readable)
        if (telo > 0.02f) {
            t2LinesBegin()
            val al = 0.18f * telo * cycleA
            for (sgn in SIGNS) {
                val cz = T2_PLATE_Z + sgn * (poleD - 1.9f); val rr = 3.4f * telo
                for (mrd in 0 until 24) {
                    val ph = mrd * T2PI / 12f; var px = 0f; var py = 0f; var pz = 0f
                    for (q in 0..12) {
                        val th = q * T2PI / 12f
                        val w = t2W(f0, T2_SPX + sin(th) * cos(ph) * rr, T2_SPY + sin(th) * sin(ph) * rr, cz + cos(th) * rr * 0.9f)
                        if (q > 0) t2Seg(px, py, pz, w[0], w[1], w[2], T2_NUCLEUS, al * 3f)
                        px = w[0]; py = w[1]; pz = w[2]
                    }
                }
            }
            t2LinesEnd(1f)
            for (sgn in SIGNS) for (k in 0 until 6) {
                val cz = T2_PLATE_Z + sgn * (poleD - 1.9f); val rr = 3.4f * telo
                val th = 0.6f + k * 0.4f; val ph = k * 1.7f
                val w = t2W(f0, T2_SPX + sin(th) * cos(ph) * rr, T2_SPY + sin(th) * sin(ph) * rr, cz + cos(th) * rr * 0.9f)
                t2Vert(w[0], w[1], w[2], T2_NPC, telo * cycleA)
            }
            t2LinesEnd(1f, true, 5f)
        }
        // the cell: rounded, elongating in anaphase, then cleaved by the contractile ring
        val d = 1.1f * anaB + 4.8f * cyto; val R = T2_CELL_R - 1.2f * cyto
        val waist = sqrt(max(0.3f, R * R - d * d))
        for (sgn in SIGNS) {
            val p = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z + sgn * d).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], R, R, R, t2BallMesh(), T2_CELL, COL_CELL_EDGE, 0.25f * cycleA, 0.15f)
            if (d < 0.01f) break
        }
        // its outline as seen from the lane, and the thin basal process still holding the sheet
        t2LinesBegin()
        for (sgn in SIGNS) {
            val cz = T2_PLATE_Z + sgn * d
            var px = 0f; var py = 0f; var pz = 0f
            for (q in 0..40) {
                val a = q * 2f * T2PI / 40f
                val w = t2W(f0, T2_SPX + cos(a) * R, T2_SPY + sin(a) * R, cz)
                if (q > 0) t2Seg(px, py, pz, w[0], w[1], w[2], T2_CELL_OUTLINE, 0.5f * cycleA)
                px = w[0]; py = w[1]; pz = w[2]
            }
            if (d < 0.01f) break
        }
        t2LinesEnd(1.5f)
        run {
            val a = t2W(f0, T2_SPX + R * 0.9f, T2_SPY, T2_PLATE_Z).copyOf(); val b = t2W(f0, T2_APICAL + 26f, T2_SPY, T2_PLATE_Z)
            drawStrut(a[0], a[1], a[2], b[0], b[1], b[2], 0.3f, T2_CELL, COL_CELL_EDGE, 0.1f)
        }
        if (cyto > 0.02f) {
            val p = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], waist, waist, 4f, t2RingMesh(), T2_ACTOMYOSIN, COL_LAMP, cyto * cycleA, 0.3f)
            if (cyto > 0.85f) {
                val a = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z - 0.5f).copyOf(); val b = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z + 0.5f)
                drawStrut(a[0], a[1], a[2], b[0], b[1], b[2], 0.25f, T2_GTP_CAP, T2_GTP_CAP, 0.6f)
            }
        }
    }
}

internal val T2_CELL_OUTLINE = floatArrayOf(0.75f, 0.9f, 0.95f, 1f)
internal val T2_CHROMOSOME_LIGHT_T2 = floatArrayOf(0.86f, 0.78f, 1f, 1f)
