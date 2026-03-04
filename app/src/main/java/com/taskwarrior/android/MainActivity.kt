package com.taskwarrior.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskwarrior.android.ui.*

class MainActivity : ComponentActivity() {
    private val viewModel: TaskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TaskwarriorTheme {
                TaskwarriorApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }
}

@Composable
fun TaskwarriorApp(viewModel: TaskViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "task_list") {
        composable("task_list") {
            TaskListScreen(
                viewModel = viewModel,
                onAddTask = { navController.navigate("add_task") },
                onTaskClick = { uuid -> navController.navigate("task_detail/$uuid") },
                onSettings = { navController.navigate("settings") }
            )
        }

        composable("add_task") {
            AddTaskScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "task_detail/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uuid = backStackEntry.arguments?.getString("uuid") ?: return@composable
            TaskDetailScreen(
                taskUuid = uuid,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
