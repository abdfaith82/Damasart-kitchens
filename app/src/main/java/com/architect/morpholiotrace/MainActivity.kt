package com.architect.morpholiotrace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// 1. DATA SCHEMAS & DOMAIN MODELS
// ============================================================================

enum class DesignDiscipline(val label: String) {
    ARCHITECTURE("Architecture"),
    INTERIOR("Interior Design"),
    LANDSCAPE("Landscape Architecture"),
    ARCHAEOLOGY("Archaeological Reconstruction"),
    INDUSTRIAL("Industrial / 3D Concept")
}

enum class UnitSystem(val label: String, val unitSuffix: String, val areaSuffix: String) {
    METRIC("Metric (SI)", "m", "m²"),
    IMPERIAL("Imperial (Architectural)", "ft", "sq ft")
}

enum class SubscriptionTier(val title: String, val price: String, val maxLayers: Int) {
    ESSENTIALS("Trace Essentials", "$4.99/yr", 3),
    PLUS("Trace Plus", "$9.99/yr", 10),
    PRO("Trace Pro (Unlimited + AI Scale)", "$29.99/yr", 999)
}

enum class ApprovalStatus(val label: String, val badgeColor: Long) {
    DRAFT("Draft Sketch", 0xFF607D8B),
    INTERNAL_MARKUP("Internal Redline", 0xFFE65100),
    CLIENT_REVIEW("Client AR/PDF Review", 0xFF1565C0),
    APPROVED_FOR_CAD("Approved for Vectorworks/CAD", 0xFF2E7D32)
}

enum class TraceTool(val label: String, val strokeWidth: Float) {
    TECHNICAL_PEN("0.25mm Rapidograph", 4f),
    CHISEL_MARKER("Chisel Marker", 14f),
    CHARCOAL("Architect Charcoal", 9f),
    SMART_FILL("Smart Fill (Live Area)", 5f),
    DIMENSION_LINE("Scale Dimension", 3f)
}

enum class PerspectiveMode(val label: String) {
    ORTHO_GRID("Ortho Scale Grid"),
    ONE_POINT("1-Point Perspective"),
    TWO_POINT("2-Point Perspective"),
    ISOMETRIC("30° Axonometric")
}

data class UserProfile(
    val userId: String = UUID.randomUUID().toString(),
    val fullName: String = "Elena Vance, AIA",
    val email: String = "e.vance@vancedesign.arch",
    val licenseNumber: String = "ARC-90412-NY",
    val discipline: DesignDiscipline = DesignDiscipline.ARCHITECTURE,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val tier: SubscriptionTier = SubscriptionTier.PRO,
    val onboardingCompleted: Boolean = false
)

data class SketchStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<Offset>,
    val tool: TraceTool,
    val colorHex: Long,
    val computedAreaSqM: Double = 0.0
)

data class TraceLayer(
    val layerId: String = UUID.randomUUID().toString(),
    val name: String,
    val opacity: Float = 0.85f,
    val yellowTraceTint: Boolean = true,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val strokes: List<SketchStroke> = emptyList()
) {
    val totalSmartFillArea: Double
        get() = strokes.filter { it.tool == TraceTool.SMART_FILL }.sumOf { it.computedAreaSqM }
}

data class WorkflowEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val fromStatus: ApprovalStatus,
    val toStatus: ApprovalStatus,
    val reviewerEmail: String,
    val comment: String,
    val autoNotificationSent: Boolean
)

data class TraceProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val clientName: String,
    val siteAddress: String,
    val scaleLabel: String,
    val metersPerHundredPx: Double,
    val perspectiveMode: PerspectiveMode,
    val status: ApprovalStatus,
    val reviewerEmail: String,
    val layers: List<TraceLayer>,
    val workflowHistory: List<WorkflowEvent> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val totalAreaSqM: Double
        get() = layers.filter { it.isVisible }.sumOf { it.totalSmartFillArea }
}

// ============================================================================
// 2. VIEWMODEL & REACTIVE BUSINESS LOGIC
// ============================================================================

class TraceArchitectViewModel : ViewModel() {

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _projects = MutableStateFlow(seedInitialProjects())
    val projects: StateFlow<List<TraceProject>> = _projects.asStateFlow()

    private val _activeProjectId = MutableStateFlow(_projects.value.first().id)
    val activeProjectId: StateFlow<String> = _activeProjectId.asStateFlow()

    fun completeOnboarding(profile: UserProfile) {
        _userProfile.value = profile.copy(onboardingCompleted = true)
    }

    fun selectProject(projectId: String) {
        _activeProjectId.value = projectId
    }

    fun createNewProject(
        title: String,
        clientName: String,
        siteAddress: String,
        scaleLabel: String,
        referenceWallMeters: Double,
        perspectiveMode: PerspectiveMode,
        reviewerEmail: String
    ): String {
        val newProj = TraceProject(
            title = title.trim(),
            clientName = clientName.trim(),
            siteAddress = siteAddress.trim(),
            scaleLabel = scaleLabel,
            metersPerHundredPx = (referenceWallMeters / 3.0).coerceIn(0.5, 50.0),
            perspectiveMode = perspectiveMode,
            status = ApprovalStatus.DRAFT,
            reviewerEmail = reviewerEmail.trim(),
            layers = listOf(
                TraceLayer(name = "01 Base Site Grid / PDF", yellowTraceTint = false),
                TraceLayer(name = "02 Yellow Trace Concept", yellowTraceTint = true),
                TraceLayer(name = "03 Smart Fill Massing", yellowTraceTint = true)
            ),
            workflowHistory = listOf(
                WorkflowEvent(
                    fromStatus = ApprovalStatus.DRAFT,
                    toStatus = ApprovalStatus.DRAFT,
                    reviewerEmail = reviewerEmail.trim(),
                    comment = "Project initialized with AI Instant Scale ($scaleLabel).",
                    autoNotificationSent = true
                )
            )
        )
        _projects.update { listOf(newProj) + it }
        _activeProjectId.value = newProj.id
        return newProj.id
    }

    fun addStrokeToLayer(
        projectId: String,
        layerId: String,
        points: List<Offset>,
        tool: TraceTool,
        colorHex: Long
    ) {
        if (points.size < 2) return
        _projects.update { list ->
            list.map { proj ->
                if (proj.id != projectId || proj.status == ApprovalStatus.APPROVED_FOR_CAD) {
                    proj
                } else {
                    val computedArea = if (tool == TraceTool.SMART_FILL && points.size >= 3) {
                        calculatePolygonAreaSqM(points, proj.metersPerHundredPx)
                    } else 0.0

                    val updatedLayers = proj.layers.map { layer ->
                        if (layer.layerId == layerId && !layer.isLocked) {
                            layer.copy(
                                strokes = layer.strokes + SketchStroke(
                                    points = points,
                                    tool = tool,
                                    colorHex = colorHex,
                                    computedAreaSqM = computedArea
                                )
                            )
                        } else layer
                    }
                    proj.copy(layers = updatedLayers, updatedAt = System.currentTimeMillis())
                }
            }
        }
    }

    fun addTraceSheet(projectId: String, sheetName: String) {
        _projects.update { list ->
            list.map { proj ->
                if (proj.id == projectId) {
                    val newLayer = TraceLayer(
                        name = sheetName.ifBlank { "Trace Sheet 0${proj.layers.size + 1}" },
                        yellowTraceTint = true
                    )
                    proj.copy(layers = proj.layers + newLayer, updatedAt = System.currentTimeMillis())
                } else proj
            }
        }
    }

    fun toggleLayerVisibility(projectId: String, layerId: String) {
        _projects.update { list ->
            list.map { proj ->
                if (proj.id == projectId) {
                    proj.copy(
                        layers = proj.layers.map {
                            if (it.layerId == layerId) it.copy(isVisible = !it.isVisible) else it
                        }
                    )
                } else proj
            }
        }
    }

    fun updateLayerOpacity(projectId: String, layerId: String, opacity: Float) {
        _projects.update { list ->
            list.map { proj ->
                if (proj.id == projectId) {
                    proj.copy(
                        layers = proj.layers.map {
                            if (it.layerId == layerId) it.copy(opacity = opacity) else it
                        }
                    )
                } else proj
            }
        }
    }

    fun clearLayerStrokes(projectId: String, layerId: String) {
        _projects.update { list ->
            list.map { proj ->
                if (proj.id == projectId && proj.status != ApprovalStatus.APPROVED_FOR_CAD) {
                    proj.copy(
                        layers = proj.layers.map {
                            if (it.layerId == layerId && !it.isLocked) it.copy(strokes = emptyList()) else it
                        }
                    )
                } else proj
            }
        }
    }

    fun advanceProjectWorkflow(
        projectId: String,
        targetStatus: ApprovalStatus,
        reviewerEmail: String,
        markupComment: String
    ) {
        _projects.update { list ->
            list.map { proj ->
                if (proj.id == projectId) {
                    val event = WorkflowEvent(
                        fromStatus = proj.status,
                        toStatus = targetStatus,
                        reviewerEmail = reviewerEmail,
                        comment = markupComment,
                        autoNotificationSent = true
                    )
                    val updatedLayers = when (targetStatus) {
                        ApprovalStatus.APPROVED_FOR_CAD -> proj.layers.map { it.copy(isLocked = true) }
                        ApprovalStatus.INTERNAL_MARKUP -> {
                            val hasRedline = proj.layers.any { it.name.contains("Redline", ignoreCase = true) }
                            if (!hasRedline) {
                                proj.layers + TraceLayer(name = "Redline Markup Overlay", opacity = 0.9f)
                            } else proj.layers
                        }
                        else -> proj.layers
                    }
                    proj.copy(
                        status = targetStatus,
                        reviewerEmail = reviewerEmail,
                        layers = updatedLayers,
                        workflowHistory = listOf(event) + proj.workflowHistory,
                        updatedAt = System.currentTimeMillis()
                    )
                } else proj
            }
        }
    }

    private fun calculatePolygonAreaSqM(points: List<Offset>, metersPer100Px: Double): Double {
        var areaPx = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            areaPx += points[i].x * points[j].y
            areaPx -= points[j].x * points[i].y
        }
        val absAreaPx = abs(areaPx) / 2.0
        val scaleFactor = (metersPer100Px / 100.0) * (metersPer100Px / 100.0)
        return (absAreaPx * scaleFactor).coerceAtLeast(1.2)
    }

    private fun seedInitialProjects(): List<TraceProject> {
        val samplePolygon1 = listOf(
            Offset(140f, 160f), Offset(460f, 160f), Offset(460f, 420f), Offset(140f, 420f)
        )
        val sampleWallLine = listOf(
            Offset(120f, 140f), Offset(480f, 140f), Offset(480f, 440f), Offset(120f, 440f), Offset(120f, 140f)
        )
        return listOf(
            TraceProject(
                title = "Helios Courtyard Pavilion",
                clientName = "Aethelgard Cultural Trust",
                siteAddress = "Plot 14, West Waterfront District",
                scaleLabel = "1:50 (Metric)",
                metersPerHundredPx = 2.5,
                perspectiveMode = PerspectiveMode.TWO_POINT,
                status = ApprovalStatus.INTERNAL_MARKUP,
                reviewerEmail = "principal@vancedesign.arch",
                layers = listOf(
                    TraceLayer(
                        name = "01 Structural Grid & Walls",
                        yellowTraceTint = false,
                        strokes = listOf(
                            SketchStroke(
                                points = sampleWallLine,
                                tool = TraceTool.TECHNICAL_PEN,
                                colorHex = 0xFF212121
                            )
                        )
                    ),
                    TraceLayer(
                        name = "02 Smart Fill Glazing Zone",
                        yellowTraceTint = true,
                        strokes = listOf(
                            SketchStroke(
                                points = samplePolygon1,
                                tool = TraceTool.SMART_FILL,
                                colorHex = 0xFF00897B,
                                computedAreaSqM = 52.0
                            )
                        )
                    )
                ),
                workflowHistory = listOf(
                    WorkflowEvent(
                        fromStatus = ApprovalStatus.DRAFT,
                        toStatus = ApprovalStatus.INTERNAL_MARKUP,
                        reviewerEmail = "principal@vancedesign.arch",
                        comment = "Verify cantilever overhang dimensions and glazing Smart Fill area.",
                        autoNotificationSent = true
                    )
                )
            ),
            TraceProject(
                title = "Temple of Apollo Stoa Reconstruction",
                clientName = "Mediterranean Archaeological Institute",
                siteAddress = "Sector B4 Excavation Trench",
                scaleLabel = "1:20 (Block Detail)",
                metersPerHundredPx = 1.0,
                perspectiveMode = PerspectiveMode.ISOMETRIC,
                status = ApprovalStatus.CLIENT_REVIEW,
                reviewerEmail = "director@medarch.org",
                layers = listOf(
                    TraceLayer(
                        name = "01 Marble Fragment Survey",
                        yellowTraceTint = true,
                        strokes = listOf(
                            SketchStroke(
                                points = listOf(Offset(180f, 200f), Offset(390f, 200f), Offset(390f, 340f), Offset(180f, 340f)),
                                tool = TraceTool.SMART_FILL,
                                colorHex = 0xFFF57C00,
                                computedAreaSqM = 18.4
                            )
                        )
                    )
                ),
                workflowHistory = listOf(
                    WorkflowEvent(
                        fromStatus = ApprovalStatus.INTERNAL_MARKUP,
                        toStatus = ApprovalStatus.CLIENT_REVIEW,
                        reviewerEmail = "director@medarch.org",
                        comment = "Doric entablature block reconstruction scaled and ready for peer review.",
                        autoNotificationSent = true
                    )
                )
            ),
            TraceProject(
                title = "SoHo Loft Adaptive Reuse",
                clientName = "Mercer Residential Partners",
                siteAddress = "412 Mercer St, Penthouse B",
                scaleLabel = "1/4\" = 1'-0\"",
                metersPerHundredPx = 2.0,
                perspectiveMode = PerspectiveMode.ONE_POINT,
                status = ApprovalStatus.APPROVED_FOR_CAD,
                reviewerEmail = "permits@mercerpartners.com",
                layers = listOf(
                    TraceLayer(
                        name = "01 Approved Demolition & Partition Plan",
                        yellowTraceTint = false,
                        isLocked = true,
                        strokes = listOf(
                            SketchStroke(
                                points = listOf(Offset(100f, 120f), Offset(520f, 120f), Offset(520f, 480f), Offset(100f, 480f)),
                                tool = TraceTool.SMART_FILL,
                                colorHex = 0xFF3949AB,
                                computedAreaSqM = 114.6
                            )
                        )
                    )
                ),
                workflowHistory = listOf(
                    WorkflowEvent(
                        fromStatus = ApprovalStatus.CLIENT_REVIEW,
                        toStatus = ApprovalStatus.APPROVED_FOR_CAD,
                        reviewerEmail = "permits@mercerpartners.com",
                        comment = "Signed off for Vectorworks BIM export. All trace layers locked.",
                        autoNotificationSent = true
                    )
                )
            )
        )
    }
}

// ============================================================================
// 3. MAIN ACTIVITY & MATERIAL 3 THEME
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MorpholioTraceTheme {
                val vm: TraceArchitectViewModel = viewModel()
                MorpholioTraceAppShell(vm)
            }
        }
    }
}

@Composable
fun MorpholioTraceTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF1B1B1F),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFF3C4),
        onPrimaryContainer = Color(0xFF261900),
        secondary = Color(0xFFD84315),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFDBD1),
        tertiary = Color(0xFF00695C),
        background = Color(0xFFF7F6F2),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFECEAE2)
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

enum class MainNavTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DASHBOARD("Projects", Icons.Default.Dashboard),
    TRACE_CANVAS("Trace Studio", Icons.Default.Architecture),
    INTAKE_FORM("New Scale", Icons.Default.AddBox),
    WORKFLOWS("Workflows", Icons.Default.Approval)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorpholioTraceAppShell(vm: TraceArchitectViewModel) {
    val profile by vm.userProfile.collectAsState()
    val projects by vm.projects.collectAsState()
    val activeProjectId by vm.activeProjectId.collectAsState()

    var selectedTab by remember { mutableStateOf(MainNavTab.DASHBOARD) }
    var showProfileDialog by remember { mutableStateOf(!profile.onboardingCompleted) }

    val activeProject = projects.find { it.id == activeProjectId } ?: projects.first()

    if (showProfileDialog) {
        OnboardingAndProfileModal(
            initialProfile = profile,
            onDismiss = { if (profile.onboardingCompleted) showProfileDialog = false },
            onSave = { updated ->
                vm.completeOnboarding(updated)
                showProfileDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "MORPHOLIO TRACE",
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp,
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "CAD + AI SCALE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${profile.fullName} • ${profile.discipline.label} (${profile.unitSystem.unitSuffix})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showProfileDialog = true }) {
                        Icon(Icons.Default.ManageAccounts, contentDescription = "Architect Profile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                MainNavTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MainNavTab.DASHBOARD -> ProjectsDatabaseDashboard(
                    projects = projects,
                    unitSystem = profile.unitSystem,
                    onOpenInStudio = { id ->
                        vm.selectProject(id)
                        selectedTab = MainNavTab.TRACE_CANVAS
                    },
                    onOpenWorkflow = { id ->
                        vm.selectProject(id)
                        selectedTab = MainNavTab.WORKFLOWS
                    },
                    onCreateNewClick = { selectedTab = MainNavTab.INTAKE_FORM }
                )

                MainNavTab.TRACE_CANVAS -> InteractiveTraceStudioScreen(
                    project = activeProject,
                    unitSystem = profile.unitSystem,
                    onAddStroke = { layerId, pts, tool, col ->
                        vm.addStrokeToLayer(activeProject.id, layerId, pts, tool, col)
                    },
                    onAddTraceLayer = { name -> vm.addTraceSheet(activeProject.id, name) },
                    onToggleLayer = { layerId -> vm.toggleLayerVisibility(activeProject.id, layerId) },
                    onOpacityChange = { layerId, alpha ->
                        vm.updateLayerOpacity(activeProject.id, layerId, alpha)
                    },
                    onClearLayer = { layerId -> vm.clearLayerStrokes(activeProject.id, layerId) }
                )

                MainNavTab.INTAKE_FORM -> DynamicProjectIntakeScreen(
                    unitSystem = profile.unitSystem,
                    onProjectCreated = { newId ->
                        vm.selectProject(newId)
                        selectedTab = MainNavTab.TRACE_CANVAS
                    },
                    onCreateProject = vm::createNewProject
                )

                MainNavTab.WORKFLOWS -> AutomatedWorkflowAndApprovalScreen(
                    project = activeProject,
                    allProjects = projects,
                    onSelectProject = vm::selectProject,
                    onAdvanceStatus = { target, email, notes ->
                        vm.advanceProjectWorkflow(activeProject.id, target, email, notes)
                    }
                )
            }
        }
    }
}

// ============================================================================
// 4. MODULE 1: USER ONBOARDING & STUDIO PROFILE CONFIGURATION
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingAndProfileModal(
    initialProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit
) {
    var fullName by remember { mutableStateOf(initialProfile.fullName) }
    var email by remember { mutableStateOf(initialProfile.email) }
    var license by remember { mutableStateOf(initialProfile.licenseNumber) }
    var discipline by remember { mutableStateOf(initialProfile.discipline) }
    var unitSystem by remember { mutableStateOf(initialProfile.unitSystem) }
    var tier by remember { mutableStateOf(initialProfile.tier) }

    val isNameValid = fullName.trim().length in 2..60
    val isEmailValid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val isLicenseValid = license.trim().length <= 20

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Studio Onboarding & Calibration", fontWeight = FontWeight.Bold)
                Text(
                    "Configure default CAD scales, subscription tier, and architect credentials.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Architect / Designer Name *") },
                    isError = !isNameValid,
                    supportingText = {
                        if (!isNameValid) Text("Required: 2 to 60 characters")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Studio Email (for PDF/USDZ Markups) *") },
                    isError = !isEmailValid,
                    supportingText = {
                        if (!isEmailValid) Text("Enter a valid email syntax (name@studio.com)")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = license,
                    onValueChange = { if (it.length <= 20) license = it },
                    label = { Text("Registration / AIA / RIBA ID") },
                    supportingText = { Text("${license.length}/20 chars") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Primary Discipline", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DesignDiscipline.entries) { item ->
                        FilterChip(
                            selected = discipline == item,
                            onClick = { discipline = item },
                            label = { Text(item.label) }
                        )
                    }
                }

                Text("Default Scale Unit System", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSystem.entries.forEach { sys ->
                        FilterChip(
                            selected = unitSystem == sys,
                            onClick = { unitSystem = sys },
                            label = { Text(sys.label) }
                        )
                    }
                }

                Text("Morpholio Subscription Tier", style = MaterialTheme.typography.labelMedium)
                SubscriptionTier.entries.forEach { sub ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tier = sub },
                        colors = CardDefaults.cardColors(
                            containerColor = if (tier == sub)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(sub.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Max Trace Layers: ${sub.maxLayers}", fontSize = 11.sp)
                            }
                            Text(sub.price, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isNameValid && isEmailValid && isLicenseValid,
                onClick = {
                    onSave(
                        initialProfile.copy(
                            fullName = fullName.trim(),
                            email = email.trim(),
                            licenseNumber = license.trim(),
                            discipline = discipline,
                            unitSystem = unitSystem,
                            tier = tier
                        )
                    )
                }
            ) {
                Text("Save Studio Profile")
            }
        }
    )
}

// ============================================================================
// 5. MODULE 2: INTERACTIVE DATABASE VIEWS & DASHBOARD
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsDatabaseDashboard(
    projects: List<TraceProject>,
    unitSystem: UnitSystem,
    onOpenInStudio: (String) -> Unit,
    onOpenWorkflow: (String) -> Unit,
    onCreateNewClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatusFilter by remember { mutableStateOf<ApprovalStatus?>(null) }

    val areaMultiplier = if (unitSystem == UnitSystem.IMPERIAL) 10.7639 else 1.0

    val filteredProjects = projects.filter { proj ->
        val matchesSearch = proj.title.contains(searchQuery, ignoreCase = true) ||
            proj.clientName.contains(searchQuery, ignoreCase = true) ||
            proj.siteAddress.contains(searchQuery, ignoreCase = true)
        val matchesStatus = selectedStatusFilter == null || proj.status == selectedStatusFilter
        matchesSearch && matchesStatus
    }

    val totalAreaAllProjects = projects.sumOf { it.totalAreaSqM } * areaMultiplier
    val totalLayersCount = projects.sumOf { it.layers.size }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Active Sets",
                    value = "${projects.size}",
                    subtitle = "$totalLayersCount Trace Sheets"
                )
                KpiMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Smart Fill Area",
                    value = "${totalAreaAllProjects.roundToInt()} ${unitSystem.areaSuffix}",
                    subtitle = "Live CAD Calculated"
                )
                KpiMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "CAD Ready",
                    value = "${projects.count { it.status == ApprovalStatus.APPROVED_FOR_CAD }}",
                    subtitle = "Signed Off"
                )
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search projects, clients, or site address...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedStatusFilter == null,
                        onClick = { selectedStatusFilter = null },
                        label = { Text("All Stages (${projects.size})") }
                    )
                }
                items(ApprovalStatus.entries) { status ->
                    FilterChip(
                        selected = selectedStatusFilter == status,
                        onClick = {
                            selectedStatusFilter = if (selectedStatusFilter == status) null else status
                        },
                        label = { Text(status.label) }
                    )
                }
            }
        }

        items(filteredProjects, key = { it.id }) { proj ->
            val convertedArea = (proj.totalAreaSqM * areaMultiplier * 10.0).roundToInt() / 10.0
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = proj.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${proj.clientName} • ${proj.siteAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = Color(proj.status.badgeColor),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = proj.status.label,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("AI SCALE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(proj.scaleLabel, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("PERSPECTIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(proj.perspectiveMode.label, fontSize = 13.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("SMART FILL AREA", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "$convertedArea ${unitSystem.areaSuffix}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${proj.layers.size} Trace Layers • Reviewer: ${proj.reviewerEmail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onOpenWorkflow(proj.id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Approval, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Approval", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { onOpenInStudio(proj.id) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Trace Studio", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onCreateNewClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Calibrate New Scale Drawing Set")
            }
        }
    }
}

@Composable
fun KpiMetricCard(modifier: Modifier = Modifier, title: String, value: String, subtitle: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================================
// 6. MODULE 3: INTERACTIVE TRACE CAD STUDIO (MULTI-LAYER + SMART FILL)
// ============================================================================

@Composable
fun InteractiveTraceStudioScreen(
    project: TraceProject,
    unitSystem: UnitSystem,
    onAddStroke: (String, List<Offset>, TraceTool, Long) -> Unit,
    onAddTraceLayer: (String) -> Unit,
    onToggleLayer: (String) -> Unit,
    onOpacityChange: (String, Float) -> Unit,
    onClearLayer: (String) -> Unit
) {
    var activeLayerId by remember(project.id) {
        mutableStateOf(project.layers.lastOrNull()?.layerId ?: "")
    }
    var selectedTool by remember { mutableStateOf(TraceTool.TECHNICAL_PEN) }
    var selectedColorHex by remember { mutableStateOf(0xFF1B1B1F) }
    var currentDragPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val activeLayer = project.layers.find { it.layerId == activeLayerId } ?: project.layers.first()
    val areaMultiplier = if (unitSystem == UnitSystem.IMPERIAL) 10.7639 else 1.0
    val liveTotalArea = ((project.totalAreaSqM * areaMultiplier) * 10.0).roundToInt() / 10.0

    val inkPalette = listOf(
        0xFF1B1B1F to "Rapidograph Black",
        0xFFD32F2F to "Redline Crimson",
        0xFF00796B to "Smart Fill Teal",
        0xFF1976D2 to "Blueprint Cobalt",
        0xFFF57C00 to "Amber Rendering"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${project.title} (${project.scaleLabel})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Active Sheet: ${activeLayer.name} ${if (activeLayer.isLocked) "[LOCKED]" else ""}",
                        fontSize = 11.sp,
                        color = Color(0xFFFFF3C4)
                    )
                }
                Surface(
                    color = Color(0xFF00695C),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "SMART FILL: $liveTotalArea ${unitSystem.areaSuffix}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TraceTool.entries) { tool ->
                    FilterChip(
                        selected = selectedTool == tool,
                        onClick = {
                            selectedTool = tool
                            if (tool == TraceTool.SMART_FILL) selectedColorHex = 0xFF00796B
                        },
                        label = { Text(tool.label, fontSize = 12.sp) }
                    )
                }
                item {
                    AssistChip(
                        onClick = {
                            val stencilPts = listOf(
                                Offset(260f, 220f), Offset(320f, 220f),
                                Offset(340f, 270f), Offset(290f, 310f),
                                Offset(240f, 270f), Offset(260f, 220f)
                            )
                            onAddStroke(activeLayer.layerId, stencilPts, TraceTool.SMART_FILL, 0xFF00796B)
                        },
                        label = { Text("+ Stamp Scale Stencil", fontSize = 12.sp) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    inkPalette.forEach { (hex, _) ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selectedColorHex == hex) 3.dp else 1.dp,
                                    color = if (selectedColorHex == hex) Color.White else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { selectedColorHex = hex }
                        )
                    }
                }
                TextButton(
                    onClick = { onClearLayer(activeLayer.layerId) },
                    enabled = !activeLayer.isLocked && project.status != ApprovalStatus.APPROVED_FOR_CAD
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear Sheet", fontSize = 12.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFECEAE2))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activeLayer.layerId, selectedTool, selectedColorHex, activeLayer.isLocked) {
                        if (!activeLayer.isLocked && project.status != ApprovalStatus.APPROVED_FOR_CAD) {
                            detectDragGestures(
                                onDragStart = { offset -> currentDragPoints = listOf(offset) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentDragPoints = currentDragPoints + change.position
                                },
                                onDragEnd = {
                                    onAddStroke(
                                        activeLayer.layerId,
                                        currentDragPoints,
                                        selectedTool,
                                        selectedColorHex
                                    )
                                    currentDragPoints = emptyList()
                                }
                            )
                        }
                    }
            ) {
                val step = 50f
                for (x in 0..(size.width / step).toInt()) {
                    drawLine(
                        color = Color(0xFFB0BEC5).copy(alpha = 0.45f),
                        start = Offset(x * step, 0f),
                        end = Offset(x * step, size.height),
                        strokeWidth = if (x % 2 == 0) 2f else 1f
                    )
                }
                for (y in 0..(size.height / step).toInt()) {
                    drawLine(
                        color = Color(0xFFB0BEC5).copy(alpha = 0.45f),
                        start = Offset(0f, y * step),
                        end = Offset(size.width, y * step),
                        strokeWidth = if (y % 2 == 0) 2f else 1f
                    )
                }

                if (project.perspectiveMode != PerspectiveMode.ORTHO_GRID) {
                    val vp = Offset(size.width / 2f, size.height * 0.25f)
                    for (angleIdx in 0..8) {
                        val targetX = (size.width / 8f) * angleIdx
                        drawLine(
                            color = Color(0xFF1976D2).copy(alpha = 0.22f),
                            start = vp,
                            end = Offset(targetX, size.height),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                        )
                    }
                }

                project.layers.filter { it.isVisible }.forEach { layer ->
                    if (layer.yellowTraceTint) {
                        drawRect(
                            color = Color(0xFFFFF59D).copy(alpha = (layer.opacity * 0.22f).coerceIn(0.05f, 0.4f))
                        )
                    }

                    layer.strokes.forEach { stroke ->
                        val path = Path().apply {
                            stroke.points.firstOrNull()?.let { moveTo(it.x, it.y) }
                            stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                            if (stroke.tool == TraceTool.SMART_FILL) close()
                        }

                        if (stroke.tool == TraceTool.SMART_FILL) {
                            drawPath(
                                path = path,
                                color = Color(stroke.colorHex).copy(alpha = 0.28f * layer.opacity)
                            )
                            drawPath(
                                path = path,
                                color = Color(stroke.colorHex).copy(alpha = layer.opacity),
                                style = Stroke(
                                    width = 4f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                                )
                            )
                        } else {
                            drawPath(
                                path = path,
                                color = Color(stroke.colorHex).copy(alpha = layer.opacity),
                                style = Stroke(
                                    width = stroke.tool.strokeWidth,
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                    }
                }

                if (currentDragPoints.size > 1) {
                    val livePath = Path().apply {
                        moveTo(currentDragPoints.first().x, currentDragPoints.first().y)
                        currentDragPoints.drop(1).forEach { lineTo(it.x, it.y) }
                        if (selectedTool == TraceTool.SMART_FILL) close()
                    }
                    if (selectedTool == TraceTool.SMART_FILL) {
                        drawPath(
                            path = livePath,
                            color = Color(selectedColorHex).copy(alpha = 0.25f)
                        )
                    }
                    drawPath(
                        path = livePath,
                        color = Color(selectedColorHex),
                        style = Stroke(width = selectedTool.strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            Surface(
                color = Color(0xCC212121),
                contentColor = Color.White,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = "RULER: 100px = ${project.metersPerHundredPx}m • ${project.perspectiveMode.label}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TRACE PAPER LAYER STACK (${project.layers.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    TextButton(
                        onClick = { onAddTraceLayer("Yellow Trace 0${project.layers.size + 1}") },
                        enabled = project.status != ApprovalStatus.APPROVED_FOR_CAD
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ Add Trace Roll", fontSize = 12.sp)
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(project.layers, key = { it.layerId }) { layer ->
                        val isSelected = layer.layerId == activeLayer.layerId
                        Card(
                            modifier = Modifier
                                .width(210.dp)
                                .clickable { activeLayerId = layer.layerId },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = layer.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onToggleLayer(layer.layerId) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle Visibility",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Slider(
                                    value = layer.opacity,
                                    onValueChange = { onOpacityChange(layer.layerId, it) },
                                    valueRange = 0.2f..1.0f,
                                    modifier = Modifier.height(22.dp)
                                )
                                Text(
                                    text = "Smart Fill: ${(layer.totalSmartFillArea * areaMultiplier).roundToInt()} ${unitSystem.areaSuffix}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 7. MODULE 4: DYNAMIC INTAKE FORM WITH FIELD VALIDATIONS
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicProjectIntakeScreen(
    unitSystem: UnitSystem,
    onProjectCreated: (String) -> Unit,
    onCreateProject: (String, String, String, String, Double, PerspectiveMode, String) -> String
) {
    var title by remember { mutableStateOf("") }
    var clientName by remember { mutableStateOf("") }
    var siteAddress by remember { mutableStateOf("") }
    var referenceLengthInput by remember { mutableStateOf("5.0") }
    var selectedScalePreset by remember { mutableStateOf("1:50 Architectural") }
    var selectedPerspective by remember { mutableStateOf(PerspectiveMode.TWO_POINT) }
    var reviewerEmail by remember { mutableStateOf("") }

    val scalePresets = listOf("1:20 Detail", "1:50 Architectural", "1:100 Site Plan", "1/4\" = 1'-0\"")

    val isTitleValid = title.trim().length in 3..80
    val isClientValid = clientName.trim().length in 2..100
    val isAddressValid = siteAddress.trim().length in 5..120
    val parsedLength = referenceLengthInput.toDoubleOrNull()
    val isLengthValid = parsedLength != null && parsedLength in 0.1..10000.0
    val isEmailValid = Patterns.EMAIL_ADDRESS.matcher(reviewerEmail.trim()).matches()

    val canSubmit = isTitleValid && isClientValid && isAddressValid && isLengthValid && isEmailValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "New CAD-Sketch & AI Instant Scale Intake",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black
        )
        Text(
            text = "Calibrate a new multi-layered drawing set with strict boundary validations.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = title,
            onValueChange = { if (it.length <= 80) title = it },
            label = { Text("Project Drawing Set Title *") },
            isError = title.isNotEmpty() && !isTitleValid,
            supportingText = {
                Text("${title.length}/80 characters (Min 3 required)")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = clientName,
            onValueChange = { if (it.length <= 100) clientName = it },
            label = { Text("Client / Commissioning Authority *") },
            isError = clientName.isNotEmpty() && !isClientValid,
            supportingText = {
                Text("${clientName.length}/100 characters (Min 2 required)")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = siteAddress,
            onValueChange = { if (it.length <= 120) siteAddress = it },
            label = { Text("Site Parcel / Excavation Coordinates *") },
            isError = siteAddress.isNotEmpty() && !isAddressValid,
            supportingText = {
                Text("Min 5 characters required (${siteAddress.length}/120)")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = referenceLengthInput,
            onValueChange = { referenceLengthInput = it },
            label = { Text("Instant Scale Reference Wall Length (${unitSystem.unitSuffix}) *") },
            isError = !isLengthValid,
            supportingText = {
                if (!isLengthValid) {
                    Text("Numeric boundary error: Enter a value between 0.1 and 10,000.0")
                } else {
                    Text("AI Scale calibrates Smart Fill area & Scale Pens from this reference wall.")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Target CAD Scale Ratio", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scalePresets) { preset ->
                FilterChip(
                    selected = selectedScalePreset == preset,
                    onClick = { selectedScalePreset = preset },
                    label = { Text(preset) }
                )
            }
        }

        Text("Perspective & Grid Assist Mode", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PerspectiveMode.entries) { mode ->
                FilterChip(
                    selected = selectedPerspective == mode,
                    onClick = { selectedPerspective = mode },
                    label = { Text(mode.label) }
                )
            }
        }

        OutlinedTextField(
            value = reviewerEmail,
            onValueChange = { reviewerEmail = it },
            label = { Text("Principal / Client Approval Email *") },
            isError = reviewerEmail.isNotEmpty() && !isEmailValid,
            supportingText = {
                if (reviewerEmail.isNotEmpty() && !isEmailValid) {
                    Text("Must match valid email syntax (e.g., reviewer@firm.com)")
                } else {
                    Text("Used for automated redline notifications and approval sign-off.")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val createdId = onCreateProject(
                    title,
                    clientName,
                    siteAddress,
                    selectedScalePreset,
                    parsedLength ?: 5.0,
                    selectedPerspective,
                    reviewerEmail
                )
                onProjectCreated(createdId)
            },
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Initialize Scaled Trace Studio", fontWeight = FontWeight.Bold)
        }
    }
}

// ============================================================================
// 8. MODULE 5: AUTOMATED WORKFLOWS (APPROVAL CHAINS & EMAIL NOTIFICATIONS)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomatedWorkflowAndApprovalScreen(
    project: TraceProject,
    allProjects: List<TraceProject>,
    onSelectProject: (String) -> Unit,
    onAdvanceStatus: (ApprovalStatus, String, String) -> Unit
) {
    val context = LocalContext.current
    var reviewerEmail by remember(project.id) { mutableStateOf(project.reviewerEmail) }
    var markupNotes by remember(project.id) {
        mutableStateOf("Verified Smart Fill areas (${project.totalAreaSqM.roundToInt()} m²) and scale dimensions.")
    }
    var sendExternalEmailIntent by remember { mutableStateOf(false) }

    val isEmailValid = Patterns.EMAIL_ADDRESS.matcher(reviewerEmail.trim()).matches()
    val isNotesValid = markupNotes.trim().length in 5..300
    val dateFormatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Automated CAD Markup & Approval Chain",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Select a drawing set to trigger status transitions, lock layers for Vectorworks export, and dispatch notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allProjects, key = { it.id }) { item ->
                    FilterChip(
                        selected = item.id == project.id,
                        onClick = { onSelectProject(item.id) },
                        label = { Text(item.title) }
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("CURRENT PIPELINE STAGE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ApprovalStatus.entries.forEach { stage ->
                            val isActive = project.status == stage
                            Surface(
                                color = if (isActive) Color(stage.badgeColor) else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.border(
                                    1.dp,
                                    Color(stage.badgeColor),
                                    RoundedCornerShape(6.dp)
                                )
                            ) {
                                Text(
                                    text = stage.name.replace("_", " "),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) Color.White else Color(stage.badgeColor),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Dispatch Review / Advance Approval State", fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = reviewerEmail,
                        onValueChange = { reviewerEmail = it },
                        label = { Text("Approver / Recipient Email *") },
                        isError = !isEmailValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = markupNotes,
                        onValueChange = { if (it.length <= 300) markupNotes = it },
                        label = { Text("Markup Redline Summary / Approval Stamp Note *") },
                        isError = !isNotesValid,
                        supportingText = { Text("${markupNotes.length}/300 chars (Min 5)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Also launch Android Email Client (PDF/USDZ summary)", fontSize = 12.sp)
                        Switch(
                            checked = sendExternalEmailIntent,
                            onCheckedChange = { sendExternalEmailIntent = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ApprovalStatus.entries.forEach { target ->
                            val enabled = isEmailValid && isNotesValid && target != project.status
                            Button(
                                onClick = {
                                    onAdvanceStatus(target, reviewerEmail.trim(), markupNotes.trim())
                                    Toast.makeText(
                                        context,
                                        "Workflow auto-updated to ${target.label} & notification logged.",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    if (sendExternalEmailIntent) {
                                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("mailto:${reviewerEmail.trim()}")
                                            putExtra(
                                                Intent.EXTRA_SUBJECT,
                                                "[Morpholio Trace CAD] ${project.title} -> ${target.label}"
                                            )
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "Project: ${project.title}\n" +
                                                    "Scale: ${project.scaleLabel}\n" +
                                                    "Calculated Smart Fill Area: ${project.totalAreaSqM.roundToInt()} m²\n" +
                                                    "Status: ${target.label}\n\n" +
                                                    "Notes:\n$markupNotes"
                                            )
                                        }
                                        runCatching { context.startActivity(intent) }
                                    }
                                },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = target.label.substringBefore(" "),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Automated Notification & Audit Log (${project.workflowHistory.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        items(project.workflowHistory, key = { it.id }) { event ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${event.fromStatus.label} ➔ ${event.toStatus.label}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = dateFormatter.format(Date(event.timestamp)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(event.comment, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "✓ Auto-dispatched to ${event.reviewerEmail}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
