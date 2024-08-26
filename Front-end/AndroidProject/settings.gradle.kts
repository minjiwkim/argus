pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://nexus.sktelecom.com/nexus/content/groups/public/")
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://nexus.sktelecom.com/nexus/content/groups/public/")
        }
        maven {
            // TMAP SDK Repository 추가
            url = uri("https://maven.tmap.co.kr")         // gpt 추천
//            url = uri("https://devrepo.tmapadmin.com/repository/tmap-sdk-release/")     // T MAP API 공식 문서
        }
    }
}

rootProject.name = "My Application1"
include(":app")
 