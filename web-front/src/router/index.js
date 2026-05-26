import { createRouter as _createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

import LoginView from '../views/LoginView.vue'
import DefaultLayout from '../layouts/DefaultLayout.vue'
import DashboardView from '../views/DashboardView.vue'
import PlanView from '../views/PlanView.vue'
import GoalsView from '../views/GoalsView.vue'
import TasksView from '../views/TasksView.vue'
import JournalsView from '../views/JournalsView.vue'
import ScheduleView from '../views/ScheduleView.vue'
import ResourcesView from '../views/ResourcesView.vue'
import PunchView from '../views/PunchView.vue'
import ProfileView from '../views/ProfileView.vue'
import Game2048View from '../views/Game2048View.vue'

export function createRouter() {
  const router = _createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
      {
        path: '/onboarding',
        redirect: (to) => ({ name: 'plan', query: to.query }),
      },
      {
        path: '/',
        component: DefaultLayout,
        children: [
          { path: '', name: 'dashboard', component: DashboardView },
          { path: 'plan', name: 'plan', component: PlanView },
          { path: 'goals', name: 'goals', component: GoalsView },
          { path: 'tasks', name: 'tasks', component: TasksView },
          { path: 'journals', name: 'journals', component: JournalsView },
          { path: 'schedule', name: 'schedule', component: ScheduleView },
          { path: 'resources', name: 'resources', component: ResourcesView },
          { path: 'punch', name: 'punch', component: PunchView },
          { path: 'profile', name: 'profile', component: ProfileView },
          { path: 'games/2048', name: 'game2048', component: Game2048View },
        ],
      },
    ],
  })

  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (to.meta.public) return true
    if (!auth.isAuthed) return { name: 'login' }
    if (!auth.me) {
      try {
        await auth.fetchMe()
      } catch (e) {
        auth.logout()
        return { name: 'login' }
      }
    }
    if (auth.me?.scheduleImported === false && to.name !== 'plan') {
      return { name: 'plan', query: { next: to.fullPath } }
    }
    return true
  })

  return router
}
