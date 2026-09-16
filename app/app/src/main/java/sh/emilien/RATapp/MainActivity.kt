package sh.emilien.RATapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.opencv.android.OpenCVLoader
import sh.emilien.RATapp.cv.CvViewModel
import sh.emilien.RATapp.cv.InferenceSink
import sh.emilien.RATapp.ui.theme.RATTheme

object Routes {
    const val CONNECTION = "connection"
    const val HOME = "home"
    const val DEBUG = "debug"
    const val RUN = "run"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must run before any org.opencv class is touched by the CV pipeline.
        if (!OpenCVLoader.initLocal()) {
            Log.e("RATCv", "OpenCV failed to load; CV features will not work")
        }
        enableEdgeToEdge()
        setContent {
            RATTheme {
                val navController = rememberNavController()
                val viewModel: BluetoothViewModel = viewModel()
                val cvViewModel: CvViewModel = viewModel()

                // The CV side sends points through the BLE layer without depending on it.
                LaunchedEffect(viewModel, cvViewModel) {
                    cvViewModel.inferenceSink = InferenceSink(viewModel::sendInferenceResult)
                }

                NavHost(navController = navController, startDestination = Routes.CONNECTION) {
                    composable(Routes.CONNECTION) {
                        ConnectionScreen(
                            onConnected = {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.CONNECTION) { inclusive = true }
                                }
                            },
                            viewModel = viewModel,
                        )
                    }
                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenDebug = { navController.navigate(Routes.DEBUG) },
                            onOpenRun = { navController.navigate(Routes.RUN) },
                            onDisconnected = {
                                navController.navigate(Routes.CONNECTION) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            viewModel = viewModel,
                            cvViewModel = cvViewModel,
                        )
                    }
                    composable(Routes.RUN) {
                        RunScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                            cvViewModel = cvViewModel,
                        )
                    }
                    composable(Routes.DEBUG) {
                        DebugScreen(
                            onDisconnected = {
                                navController.navigate(Routes.CONNECTION) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            viewModel = viewModel,
                            cvViewModel = cvViewModel,
                        )
                    }
                }
            }
        }
    }
}
