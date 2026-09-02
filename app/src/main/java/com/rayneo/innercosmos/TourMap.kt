package com.rayneo.innercosmos

/** The procedural landmark drawn at a stop. */
enum class Scene {
    THRESHOLD, AIRWAY, ALVEOLUS, BLOOD, HEART, SENTINEL, NEURON, MEMBRANE, MITOCHONDRION, NUCLEUS, RIBOSOME, ATOM, LOOKBACK,
    MOUTH, GUT, PHAGE, LIVER, KIDNEY, MUSCLE, MARROW, VDJ, HIGHWAY, FACTORY, MOTOR, DIVISION,
    CAVITY, DONOR, STORED, WOUND, SUTURE, SEPSIS
}

/** Ambience family of a stop: the synthesized sound bed and which particles drift past. */
enum class Amb { AIR, BLOOD, NEURAL, CYTO, ATOM, LOOKBACK, GUT, MUSCLE, MOTOR }

/**
 * One stop on a rail. Positions are world units (stops ~16 apart down -z); [radius] is the
 * passage radius there; [wall] its colour; [shipLenM] the Mote's length at the stop; [scene] the
 * landmark; [amb] the ambience; [mapX]/[mapY] the marker position on the body-map inset (figure
 * coordinates, 100 x 150) with its label; [scaleLabel] the ladder text for the depth menu.
 */
class TourNode(
    val name: String, val x: Float, val y: Float, val z: Float, val radius: Float, val wall: FloatArray,
    val shipLenM: Double, val scene: Scene, val amb: Amb,
    val mapX: Float, val mapY: Float, val mapLabel: String, val scaleLabel: String
)

/**
 * A complete tour: its stops, the script asset that narrates it, the Mote-length breakpoints for
 * the HUD (rail progress -> metres, log-linear between points, mirroring where the script's
 * shrink / grow cues land) and the rail positions where the arm probes reach out.
 */
class TourMap(
    val id: Int, val title: String, val subtitle: String, val hudTitle: String, val scriptAsset: String,
    val nodes: List<TourNode>, val lengthKeys: FloatArray, val lengthM: DoubleArray, val armStops: FloatArray
) {
    /** Rail progress beyond which the visible airflow is gone: 0.7 past the last AIR stop (or -1 if none). */
    val airEnd: Float = nodes.indexOfLast { it.amb == Amb.AIR }.let { if (it < 0) -1f else it + 0.7f }
    val durationLabel: String get() = when (id) { 1 -> "33 MIN"; 2 -> "35 MIN"; else -> "28 MIN" }
}

object Tours {
    private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    /** Tour I — THE DESCENT: nose to carbon atom, one power of ten per stop. */
    val DESCENT = TourMap(
        id = 1, title = "THE DESCENT", subtitle = "NOSE TO ATOM  ·  13 STOPS", hudTitle = "INNERCOSMOS EXPEDITION", scriptAsset = "tour_script.json",
        nodes = listOf(
            TourNode("THE THRESHOLD", 0.0f, 0.0f, 0f, 4.2f, rgb(0.96f, 0.62f, 0.56f), 12.0, Scene.THRESHOLD, Amb.AIR, 50f, 19f, "NOSE", "12 m → 12 mm"),
            TourNode("THE AIRWAY", 2.4f, 0.3f, -16f, 2.6f, rgb(0.90f, 0.42f, 0.48f), 1.2e-3, Scene.AIRWAY, Amb.AIR, 50f, 34f, "TRACHEA", "1.2 mm"),
            TourNode("THE ALVEOLUS", -2.2f, -0.2f, -32f, 3.6f, rgb(0.96f, 0.78f, 0.78f), 1.2e-4, Scene.ALVEOLUS, Amb.AIR, 59f, 60f, "LUNG · ALVEOLUS", "120 µm"),
            TourNode("THE BLOODSTREAM", 2.0f, 0.4f, -48f, 1.6f, rgb(0.62f, 0.08f, 0.10f), 1.2e-5, Scene.BLOOD, Amb.BLOOD, 56f, 53f, "PULMONARY VEIN", "12 µm"),
            TourNode("THE HEART", -2.6f, 0.0f, -64f, 6.0f, rgb(0.55f, 0.10f, 0.16f), 1.2e-5, Scene.HEART, Amb.BLOOD, 54f, 50f, "HEART", "12 µm"),
            TourNode("THE SENTINEL", 2.4f, -0.3f, -80f, 3.0f, rgb(0.60f, 0.12f, 0.16f), 1.2e-5, Scene.SENTINEL, Amb.BLOOD, 51f, 30f, "CAROTID ARTERY", "12 µm"),
            TourNode("THE NEURON", -2.0f, 0.3f, -96f, 2.2f, rgb(0.45f, 0.32f, 0.80f), 1.2e-6, Scene.NEURON, Amb.NEURAL, 50f, 12f, "BRAIN · NEURON", "1.2 µm → 120 nm"),
            TourNode("THE MEMBRANE", 2.2f, 0.2f, -112f, 2.8f, rgb(0.20f, 0.68f, 0.64f), 1.2e-7, Scene.MEMBRANE, Amb.CYTO, 50f, 12f, "CELL MEMBRANE", "120 nm"),
            TourNode("THE MITOCHONDRION", -2.4f, -0.2f, -128f, 2.0f, rgb(0.88f, 0.48f, 0.20f), 1.2e-7, Scene.MITOCHONDRION, Amb.CYTO, 50f, 12f, "MITOCHONDRION", "120 nm"),
            TourNode("THE NUCLEUS", 2.0f, 0.3f, -144f, 3.2f, rgb(0.32f, 0.28f, 0.82f), 1.2e-8, Scene.NUCLEUS, Amb.CYTO, 50f, 12f, "CELL NUCLEUS", "12 nm"),
            TourNode("THE RIBOSOME", -2.2f, 0.0f, -160f, 2.4f, rgb(0.20f, 0.58f, 0.60f), 1.2e-8, Scene.RIBOSOME, Amb.CYTO, 50f, 12f, "RIBOSOME", "12 nm"),
            TourNode("THE ATOM", 1.6f, -0.2f, -176f, 7.0f, rgb(0.06f, 0.06f, 0.14f), 1.2e-11, Scene.ATOM, Amb.ATOM, 50f, 12f, "CARBON ATOM", "12 pm"),
            TourNode("THE LOOK BACK", 0.0f, 0.2f, -194f, 9.0f, rgb(0.38f, 0.22f, 0.36f), 12.0, Scene.LOOKBACK, Amb.LOOKBACK, 50f, 60f, "WHOLE BODY", "12 pm → 12 m")
        ),
        lengthKeys = floatArrayOf(0f, 0.3f, 0.5f, 1f, 2f, 3f, 5f, 6f, 6.5f, 8f, 9f, 10f, 11f, 12f),
        lengthM = doubleArrayOf(12.0, 12.0, 1.2e-2, 1.2e-3, 1.2e-4, 1.2e-5, 1.2e-5, 1.2e-6, 1.2e-7, 1.2e-7, 1.2e-8, 1.2e-8, 1.2e-11, 12.0),
        armStops = floatArrayOf(2.05f, 5.05f, 7.02f, 9.15f, 10.05f)   // alveolus, sentinel, membrane, helix, ribosome
    )

    /**
     * Tour II — THE LIVING MACHINE: mouth to a dividing cell by way of the gut's microbiome and
     * its phages, the liver, the kidney, muscle, marrow, V(D)J recombination, kinesin on its
     * microtubule highway, the protein factory, a single ATP synthase and mitosis. The ladder
     * climbs back up several times (the "grow" cue) instead of only descending.
     */
    val MACHINE = TourMap(
        id = 2, title = "THE LIVING MACHINE", subtitle = "MOUTH TO MITOSIS  ·  13 STOPS", hudTitle = "INNERCOSMOS II · LIVING MACHINE", scriptAsset = "tour2_script.json",
        nodes = listOf(
            TourNode("THE MOUTH", 0.0f, 0.0f, 0f, 4.2f, rgb(0.92f, 0.50f, 0.52f), 12.0, Scene.MOUTH, Amb.AIR, 50f, 20f, "MOUTH", "12 m → 12 mm"),
            TourNode("THE GUT", 2.4f, 0.3f, -16f, 3.0f, rgb(0.95f, 0.55f, 0.55f), 1.2e-3, Scene.GUT, Amb.GUT, 50f, 68f, "SMALL INTESTINE", "1.2 mm → 12 µm"),
            TourNode("THE PHAGE", -2.2f, -0.2f, -32f, 3.4f, rgb(0.62f, 0.66f, 0.42f), 1.2e-7, Scene.PHAGE, Amb.GUT, 52f, 72f, "GUT · PHAGE", "12 µm → 120 nm"),
            TourNode("THE LIVER", 2.0f, 0.4f, -48f, 2.4f, rgb(0.66f, 0.20f, 0.16f), 1.2e-5, Scene.LIVER, Amb.BLOOD, 43f, 52f, "LIVER", "12 µm"),
            TourNode("THE KIDNEY", -2.6f, 0.0f, -64f, 3.0f, rgb(0.78f, 0.38f, 0.38f), 1.2e-5, Scene.KIDNEY, Amb.BLOOD, 60f, 62f, "KIDNEY", "12 µm"),
            TourNode("THE MUSCLE", 2.4f, -0.3f, -80f, 2.6f, rgb(0.74f, 0.22f, 0.24f), 1.2e-6, Scene.MUSCLE, Amb.MUSCLE, 27f, 55f, "MUSCLE · ARM", "12 µm → 1.2 µm"),
            TourNode("THE MARROW", -2.0f, 0.3f, -96f, 3.2f, rgb(0.90f, 0.82f, 0.70f), 1.2e-5, Scene.MARROW, Amb.CYTO, 40f, 118f, "BONE MARROW", "1.2 µm → 12 µm"),
            TourNode("THE SHUFFLE", 2.2f, 0.2f, -112f, 3.0f, rgb(0.34f, 0.30f, 0.82f), 1.2e-8, Scene.VDJ, Amb.CYTO, 40f, 118f, "MARROW · B CELL", "12 nm"),
            TourNode("THE HIGHWAY", -2.4f, -0.2f, -128f, 3.4f, rgb(0.20f, 0.60f, 0.62f), 1.2e-7, Scene.HIGHWAY, Amb.CYTO, 43f, 52f, "LIVER CELL", "120 nm"),
            TourNode("THE FACTORY", 2.0f, 0.3f, -144f, 2.6f, rgb(0.24f, 0.55f, 0.66f), 1.2e-8, Scene.FACTORY, Amb.CYTO, 54f, 58f, "PANCREAS · ER", "12 nm"),
            TourNode("THE MOTOR", -2.2f, 0.0f, -160f, 2.8f, rgb(0.88f, 0.48f, 0.20f), 1.2e-9, Scene.MOTOR, Amb.MOTOR, 54f, 50f, "HEART MUSCLE", "1.2 nm"),
            TourNode("THE DIVISION", 1.6f, -0.2f, -176f, 4.0f, rgb(0.26f, 0.50f, 0.60f), 1.2e-6, Scene.DIVISION, Amb.CYTO, 50f, 68f, "GUT LINING", "1.2 µm"),
            TourNode("THE LOOK BACK", 0.0f, 0.2f, -194f, 9.0f, rgb(0.38f, 0.22f, 0.36f), 12.0, Scene.LOOKBACK, Amb.LOOKBACK, 50f, 60f, "WHOLE BODY", "1.2 µm → 12 m")
        ),
        // Derived from the script's own shrink / grow cues (see SCRIPT2.md): the HUD's rung always
        // matches the figure the crew just said.
        lengthKeys = floatArrayOf(0f, 0.002f, 0.006f, 0.074f, 0.086f, 1.074f, 1.086f, 1.574f, 1.586f, 2.574f, 2.586f, 4.074f, 4.086f, 5.074f, 5.086f, 6.074f, 6.086f, 7.074f, 7.086f, 8.074f, 8.086f, 9.074f, 9.086f, 9.1715f, 9.1835f, 9.269f, 9.281f, 10.074f, 10.086f, 11.074f, 11.98f, 12f),
        lengthM = doubleArrayOf(12.0, 12.0, 0.012, 0.012, 0.0012, 0.0012, 1.2e-05, 1.2e-05, 1.2e-07, 1.2e-07, 1.2e-05, 1.2e-05, 1.2e-06, 1.2e-06, 1.2e-05, 1.2e-05, 1.2e-08, 1.2e-08, 1.2e-07, 1.2e-07, 1.2e-08, 1.2e-08, 5.5e-09, 5.5e-09, 2.6e-09, 2.6e-09, 1.2e-09, 1.2e-09, 1.2e-06, 1.2e-06, 12.0, 12.0),
        armStops = floatArrayOf(0.9f, 1.91f, 2.17f, 5.05f, 8.05f, 9.08f, 10.05f)   // villi, phage landing and lysis, sarcomere, kinesin, factory, ATP synthase
    )

    /**
     * Chapter III — BETHUNE: the same ship, but the body is the stage for a life. Henry Norman
     * Bethune (1890-1939): the Montreal surgeon whose own tuberculosis turned him into a
     * humanitarian, who built one of the first mobile blood-transfusion services in Spain, and who
     * spent his last twenty months operating for, and training, the Eighth Route Army in China,
     * where he is known as Bai Qiu'en. Ten stops, each a place in the body his work touched.
     */
    val BETHUNE = TourMap(
        id = 3, title = "BETHUNE", subtitle = "ONE SURGEON  \u00b7  10 STOPS", hudTitle = "INNERCOSMOS III \u00b7 BETHUNE", scriptAsset = "tour3_script.json",
        nodes = listOf(
            TourNode("THE CAVITY", 0.0f, 0.0f, 0f, 4.0f, rgb(0.90f, 0.72f, 0.70f), 1.2e-4, Scene.CAVITY, Amb.AIR, 44f, 52f, "LUNG \u00b7 CAVITY", "120 \u00b5m"),
            TourNode("THE VEIN", 2.4f, 0.3f, -16f, 2.2f, rgb(0.58f, 0.10f, 0.16f), 1.2e-5, Scene.DONOR, Amb.BLOOD, 22f, 66f, "DONOR'S VEIN", "12 \u00b5m"),
            TourNode("THE BOTTLE", -2.2f, -0.2f, -32f, 3.0f, rgb(0.34f, 0.16f, 0.26f), 1.2e-5, Scene.STORED, Amb.CYTO, 14f, 80f, "STORED BLOOD", "12 \u00b5m"),
            TourNode("THE FRONT", 2.0f, 0.4f, -48f, 2.8f, rgb(0.70f, 0.20f, 0.18f), 1.2e-5, Scene.WOUND, Amb.BLOOD, 40f, 105f, "THE WOUND", "12 \u00b5m"),
            TourNode("THE TRANSFUSION", -2.6f, 0.0f, -64f, 2.0f, rgb(0.62f, 0.08f, 0.10f), 1.2e-5, Scene.BLOOD, Amb.BLOOD, 24f, 70f, "TRANSFUSION", "12 \u00b5m"),
            TourNode("THE TABLE", 2.4f, -0.3f, -80f, 3.2f, rgb(0.78f, 0.34f, 0.32f), 1.2e-4, Scene.SUTURE, Amb.BLOOD, 50f, 60f, "THE TABLE", "120 \u00b5m"),
            TourNode("THE STUDENTS", -2.0f, 0.3f, -96f, 3.2f, rgb(0.90f, 0.82f, 0.70f), 1.2e-5, Scene.MARROW, Amb.CYTO, 39f, 118f, "BONE MARROW", "12 \u00b5m"),
            TourNode("THE CUT", 2.2f, 0.2f, -112f, 2.6f, rgb(0.82f, 0.40f, 0.38f), 1.2e-5, Scene.WOUND, Amb.CYTO, 19f, 76f, "A CUT FINGER", "12 \u00b5m"),
            TourNode("THE FEVER", -2.4f, -0.2f, -128f, 2.4f, rgb(0.66f, 0.10f, 0.14f), 1.2e-6, Scene.SEPSIS, Amb.BLOOD, 54f, 50f, "BLOODSTREAM", "1.2 \u00b5m"),
            TourNode("THE MEMORY", 0.0f, 0.2f, -146f, 9.0f, rgb(0.38f, 0.22f, 0.36f), 12.0, Scene.LOOKBACK, Amb.LOOKBACK, 50f, 60f, "WHOLE BODY", "1.2 \u00b5m \u2192 12 m")
        ),
        // One decade down into the bacteria at the fever, and the long climb home over the last leg.
        lengthKeys = floatArrayOf(0f, 0.9f, 1.1f, 4.9f, 5.1f, 5.9f, 6.1f, 7.9f, 8.1f, 8.6f, 9f),
        lengthM = doubleArrayOf(1.2e-4, 1.2e-4, 1.2e-5, 1.2e-5, 1.2e-4, 1.2e-4, 1.2e-5, 1.2e-5, 1.2e-6, 1.2e-6, 12.0),
        armStops = floatArrayOf(3.05f, 5.05f, 7.05f)      // the wound, the sutures, the cut
    )

    val ALL = listOf(DESCENT, MACHINE, BETHUNE)
    fun byId(id: Int): TourMap = ALL.firstOrNull { it.id == id } ?: DESCENT
}
